package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.qualitycare.web.NursingQualityController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v40：病案室待编码/待归档工作队列（只读）。
 *
 * <p>造两个出院患者——一个已编码已归档（收尾完成，不该在队列里），一个未编码未归档（该在），
 * 断言范围过滤 / 缺项清单 / 超期标记按 sys_config 阈值生效。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class V40MrWorkqueueTest {

    @Autowired InpatientService inpatientService;
    @Autowired NursingQualityController nursingQualityController;
    @Autowired PatientService patientService;
    @Autowired cn.hip.platform.core.service.ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void evict() {
        configReader.evictAll();
    }

    private Long admit(String name) {
        Patient p = new Patient();
        p.setName(name + System.nanoTime());
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        return inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎", new BigDecimal("500"), "CASH", null).getId();
    }

    private void rec(Long admId, String type, boolean signed) {
        jdbc.update("insert into inp_medical_record(admission_id, record_type, title, content, signature) "
                + "values (?,?,?,?,?)", admId, type, type, "内容", signed ? "SIG" : null);
    }

    private String admissionNo(Long admId) {
        return jdbc.queryForObject("select admission_no from inp_admission where id = ?", String.class, admId);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items() {
        return (List<Map<String, Object>>) nursingQualityController.mrWorkqueue().getData().get("items");
    }

    private Map<String, Object> rowOf(List<Map<String, Object>> items, String admissionNo) {
        return items.stream().filter(i -> admissionNo.equals(i.get("admissionNo"))).findFirst().orElse(null);
    }

    @Test
    void queueListsOnlyUnfinishedWithMissingListAndOverdueFlag() {
        // ① 收尾完成：病历齐、已编码、已归档 → 不该在队列里
        Long done = admit("病案收尾完成");
        rec(done, "ADMISSION", false);
        rec(done, "PROGRESS", false);
        rec(done, "DISCHARGE", true);
        inpatientService.discharge(done, null, "CASH");
        jdbc.update("update inp_admission set discharge_diag_icd = 'J18.9', discharge_diag_name = '肺炎' where id = ?", done);
        assertEquals(0, nursingQualityController.archive(done).getCode(), "补齐后应能归档");
        String doneNo = admissionNo(done);

        // ② 未收尾：病历不全、未编码、未归档 → 该在队列里
        Long todo = admit("病案待收尾");
        inpatientService.discharge(todo, null, "CASH");
        String todoNo = admissionNo(todo);

        var items = items();
        assertNull(rowOf(items, doneNo), "已编码已归档的病案不应进待办队列");
        var row = rowOf(items, todoNo);
        assertNotNull(row, "未编码未归档的出院病案应进待办队列");
        assertEquals(Boolean.FALSE, row.get("coded"));
        assertEquals(Boolean.FALSE, row.get("archived"));

        @SuppressWarnings("unchecked")
        var missing = (List<String>) row.get("missing");
        assertFalse(missing.isEmpty(), "缺项清单应非空");
        assertTrue(missing.contains("缺入院记录") && missing.contains("缺出院小结"), missing.toString());
        assertEquals(missing.size(), row.get("missingCount"));

        // 刚出院（0 天）：默认阈值 3 天，不超期
        assertEquals(Boolean.FALSE, row.get("overdue"), "刚出院不应标超期");

        // ③ 超期标记按 sys_config 阈值生效：出院回拨 10 天
        jdbc.update("update inp_admission set discharged_at = now() - interval '10 days' where id = ?", todo);
        jdbc.update("update sys_config set cfg_value = '5' where cfg_key = 'mr.archive.overdue_days'");
        configReader.evict("mr.archive.overdue_days");   // 既有坑：不 evict 要等 30 秒 TTL
        var overdueRow = rowOf(items(), todoNo);
        assertNotNull(overdueRow);
        assertEquals(10, overdueRow.get("dischargedDays"));
        assertEquals(Boolean.TRUE, overdueRow.get("overdue"), "出院 10 天 > 阈值 5 天应标超期");

        // 阈值调到 30 天 → 同一行不再超期（证明确实读的是 sys_config 而非硬编码）
        jdbc.update("update sys_config set cfg_value = '30' where cfg_key = 'mr.archive.overdue_days'");
        configReader.evict("mr.archive.overdue_days");
        var relaxed = nursingQualityController.mrWorkqueue().getData();
        assertEquals(30, relaxed.get("overdueDays"));
        @SuppressWarnings("unchecked")
        var relaxedItems = (List<Map<String, Object>>) relaxed.get("items");
        assertEquals(Boolean.FALSE, rowOf(relaxedItems, todoNo).get("overdue"), "阈值放宽后不应再标超期");
    }

    @Test
    void codedButNotArchivedStaysInQueueAndArchiveClearsIt() {
        Long admId = admit("已编码未归档");
        inpatientService.discharge(admId, null, "CASH");
        jdbc.update("update inp_admission set discharge_diag_icd = 'J18.9', discharge_diag_name = '肺炎' where id = ?", admId);
        String no = admissionNo(admId);

        var row = rowOf(items(), no);
        assertNotNull(row, "已编码但未归档仍属待办");
        assertEquals(Boolean.TRUE, row.get("coded"));
        assertEquals(Boolean.FALSE, row.get("archived"));

        // 默认 warn 模式：病历不全也放行，但返回 warning——前端须展示
        var r = nursingQualityController.archive(admId);
        assertEquals(0, r.getCode());
        assertNotNull(r.getData(), "warn 模式不完整归档应带 warning");
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) r.getData();
        assertTrue(String.valueOf(data.get("warning")).contains("病历不完整已放行"), data.toString());

        assertNull(rowOf(items(), no), "编码+归档均完成后应移出队列");
    }
}
