package cn.hip.server;

import cn.hip.inpatient.service.EmrIntegrityService;
import cn.hip.inpatient.service.InpatientService;
import cn.hip.inpatient.web.InpEmrController;
import cn.hip.medtech.web.MedTechController;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v42 第 5 项：病历模板可用化 / PREOP 幽灵类型修复 / 病历正文放宽（V133）。
 *
 * <p>本类**只读调用** EmrIntegrityService，不改其判定逻辑——PREOP 的服务端口径自 v35 起就是对的，
 * 缺的一直是录入入口。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class V42EmrTemplateTest {

    @Autowired EmrIntegrityService emrIntegrityService;
    @Autowired InpatientService inpatientService;
    @Autowired InpEmrController inpEmrController;
    @Autowired MedTechController medTech;
    @Autowired PatientService patientService;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;

    private final Authentication admin = new UsernamePasswordAuthenticationToken("admin", null, List.of());

    private Long admit(String name) {
        Patient p = new Patient();
        p.setName(name + System.nanoTime());
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        return inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎", new BigDecimal("500"), "CASH", null).getId();
    }

    /**
     * 回归：PREOP 幽灵类型。
     *
     * <p>修复前全仓 grep 'PREOP' 只有 EmrIntegrityService:62 一处命中——医生站病历类型下拉只有五项
     * （ADMISSION/FIRST_PROGRESS/PROGRESS/ROUND/DISCHARGE），没有术前小结，任何 E2E 也从不写 PREOP。
     * 后果：**每个手术病例都常亮一条无法自救的「缺术前小结」**，emr.gate.discharge 一旦切 block
     * 就直接出不了院。修法是补录入入口（下拉项 + 中文名），不动服务端判定、不加写路径白名单。
     *
     * <p>本用例钉死的正是这条价值链：缺项出现 → 走既有 POST /records 写一条 PREOP → 缺项消失。
     * 若有人日后给 POST /records 加了 recordType 白名单而漏了 PREOP，这里会立刻红。
     */
    @Test
    void preopRecordClearsSurgeryMissingItem() {
        Long admId = admit("PREOP幽灵");
        jdbc.update("insert into inp_surgery(admission_id, procedure_name, status) values (?, '阑尾切除术', 'SCHEDULED')",
                admId);

        var before = emrIntegrityService.check(admId);
        assertTrue(before.contains("缺术前小结"), "手术病例应出「缺术前小结」，实际：" + before);

        // 医生站下拉新增的「术前小结」走的就是这条既有通道（POST /records 本就吃任意 recordType）
        var saved = inpEmrController.addRecord(admId,
                new InpEmrController.SaveRecordRequest("PREOP", "术前小结", "术前讨论：诊断明确，拟行阑尾切除术。"), admin);
        assertEquals(0, saved.getCode(), "PREOP 不应被写路径拒绝（本版零核心写路径改动）");
        assertEquals("PREOP", saved.getData().getRecordType(), "recordType 须逐字节落库，不得被兜底成 PROGRESS");
        em.flush();   // EmrIntegrityService 走 JdbcTemplate，须先把 JPA 侧刷进同一事务

        var after = emrIntegrityService.check(admId);
        assertFalse(after.contains("缺术前小结"), "写入 PREOP 后缺项应消失，实际：" + after);
        // 护栏：只补录入入口，不动 EmrIntegrityService 的其余判定——手术记录/知情同意仍照旧缺
        assertTrue(after.contains("缺手术记录"), after.toString());
        assertTrue(after.contains("缺手术知情同意书"), after.toString());
    }

    /**
     * V133：inp_medical_record.content varchar(4000) → text。
     * 模板渲染后的多段病历必然超 4000（门诊侧同类五字段合计已 5512 字符），放宽前这条写入会
     * 在数据库层直接抛 22001 value too long。
     */
    @Test
    void contentBeyond4000CharsRoundTrips() {
        Long admId = admit("长病历");
        String longText = "术前小结·模板渲染段落。".repeat(600);   // 7200 字符
        assertTrue(longText.length() > 4000);

        var saved = inpEmrController.addRecord(admId,
                new InpEmrController.SaveRecordRequest("PREOP", "模板渲染长文本", longText), admin);
        assertEquals(0, saved.getCode());
        em.flush();
        em.clear();   // 绕开一级缓存，确保下面读到的是数据库里的那一份

        var back = inpEmrController.records(admId).getData().get(0);
        assertEquals(longText.length(), back.getContent().length(), "长文本不得被截断");
        assertEquals(longText, back.getContent());

        // 列类型确已放宽（若 V133 被回退，本断言先于业务红）
        assertEquals("text", jdbc.queryForObject("""
                select data_type from information_schema.columns
                where table_name = 'inp_medical_record' and column_name = 'content'
                """, String.class));
    }

    /**
     * 病历模板下拉取数口径：type=EMR + deptId。
     * 后端 deptId 过滤是「本科室 <b>或</b> 全院通用（dept_id is null）」——医生站下拉正是靠这一条
     * 同时拿到科室专属模板与全院通用模板；别科模板不得串入，RIS 报告模板也不得串入。
     */
    @Test
    void emrTemplatesFilteredByTypeAndDept() {
        Long mine = jdbc.queryForObject("select id from sys_dept where code = 'OUTP_IM'", Long.class);
        Long other = jdbc.queryForObject("select id from sys_dept where code = 'OUTP_SURG'", Long.class);

        medTech.createTemplate(new MedTechController.EmrTemplateReq(
                mine, "入院记录·本科室v42", "主诉：\n现病史：\n既往史：", "EMR"));
        medTech.createTemplate(new MedTechController.EmrTemplateReq(
                null, "病程记录·全院通用v42", "今日查房：\n处理：", "EMR"));
        medTech.createTemplate(new MedTechController.EmrTemplateReq(
                other, "他科模板v42", "不该出现在本科室下拉", "EMR"));
        medTech.createTemplate(new MedTechController.EmrTemplateReq(
                mine, "胸片报告·本科室v42", "两肺纹理清晰", "RIS"));

        var names = medTech.templates(mine, "EMR").getData().stream().map(t -> String.valueOf(t.get("name"))).toList();
        assertTrue(names.contains("入院记录·本科室v42"), names.toString());
        assertTrue(names.contains("病程记录·全院通用v42"), "科室过滤须带出全院通用模板（dept_id is null）：" + names);
        assertFalse(names.contains("他科模板v42"), "别科模板不得串入：" + names);
        assertFalse(names.contains("胸片报告·本科室v42"), "RIS 报告模板不得串入病历模板下拉：" + names);
        assertTrue(medTech.templates(mine, "EMR").getData().stream()
                .allMatch(t -> "EMR".equals(t.get("template_type"))));
    }
}
