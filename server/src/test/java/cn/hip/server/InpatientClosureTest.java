package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.inpatient.web.InpatientController;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import jakarta.persistence.EntityManager;
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
 * 住院线"收尾环"回归：预交款不足提醒（阻塞1）、转科原因留痕（阻塞3）、
 * 每日费用清单打印数据集（打印1）、出院小结打印数据集（打印2）。
 * 走服务层 + 控制器只读端点，与 Phase113FinanceTest 同风格（@Transactional 内 flush 让 jdbc 可见）。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class InpatientClosureTest {

    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired InpatientService inpatientService;
    @Autowired InpatientController inpatientController;
    @Autowired PatientService patientService;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private Long newPatient(String name) {
        Patient p = new Patient();
        p.setName(name);
        p.setSex("M");
        return patientService.register(p).getId();
    }

    private Long freeBed() {
        return jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
    }

    /** 阻塞1：账户端点口径——余额 = 押金 - 已执行医嘱费用；欠费即 balance<0。补交后转正。 */
    @Test
    void accountStatusReflectsDepositAndExecutedCost() {
        Long pid = newPatient("欠费提醒C1");
        Long bedId = freeBed();
        // 入院不缴押金（deposit=null 跳过），刻意制造欠费
        Long admId = inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎",
                null, "CASH", null).getId();
        var drug = seeds.anyDrug();
        BigDecimal price = drug.getPrice();
        assertTrue(price.signum() > 0, "测试前置：药品单价须 > 0");
        var order = inpatientService.createOrders(admId,
                List.of(new InpatientService.OrderLine("DRUG", drug.getId(), 1, "口服", "qd", "1粒")), null).get(0);
        inpatientService.execute(order.getId(), null);
        entityManager.flush();

        Map<String, Object> acc = inpatientController.account(admId).getData();
        assertEquals(0, ((BigDecimal) acc.get("executedAmount")).compareTo(price), "已发生费用=已执行医嘱金额");
        assertEquals(0, ((BigDecimal) acc.get("balance")).compareTo(price.negate()), "余额=押金0-费用");
        assertEquals(Boolean.TRUE, acc.get("owed"), "费用>押金必须判欠费");

        // 补交两倍押金后不再欠费
        inpatientService.addDeposit(admId, price.add(price), "CASH", null);
        entityManager.flush();
        Map<String, Object> acc2 = inpatientController.account(admId).getData();
        assertEquals(Boolean.FALSE, acc2.get("owed"), "补足押金后不应再欠费");
        assertEquals(0, ((BigDecimal) acc2.get("balance")).compareTo(price), "余额=2*费用-费用");
    }

    /** 阻塞3：转科带原因落地 inp_transfer_log，转科历史端点能读回原因/目标床。 */
    @Test
    void transferPersistsReasonAndListsHistory() {
        Long pid = newPatient("转科原因C3");
        Long bedA = freeBed();
        Long admId = inpatientService.admit(pid, 1L, bedA, null, "J18.9", "肺炎",
                new BigDecimal("500"), "CASH", null).getId();
        entityManager.flush();
        // 另取一张与 bedA 不同的空床作为目标
        Long bedB = jdbc.queryForObject(
                "select id from inp_bed where status = 'FREE' and id <> ? limit 1", Long.class, bedA);
        String bedBNo = jdbc.queryForObject("select bed_no from inp_bed where id = ?", String.class, bedB);

        var adm = inpatientService.transfer(admId, 1L, bedB, "病情变化需专科处理", null);
        entityManager.flush();
        assertEquals(bedB, adm.getBedId(), "转科后当前床位应为目标床");

        String reason = jdbc.queryForObject(
                "select reason from inp_transfer_log where admission_id = ? order by id desc limit 1",
                String.class, admId);
        assertEquals("病情变化需专科处理", reason, "转科原因必须落地");

        List<Map<String, Object>> history = inpatientController.transfers(admId).getData();
        assertEquals(1, history.size());
        assertEquals("病情变化需专科处理", history.get(0).get("reason"));
        assertEquals(bedBNo, history.get(0).get("to_bed_no"), "历史应含目标床号");
    }

    /** 打印1：每日费用清单数据集含患者姓名/住院号、当日按项费用与账户押金余额。 */
    @Test
    void dailyFeePrintDatasetAssembled() {
        Long pid = newPatient("日清单打印P1");
        Long bedId = freeBed();
        Long admId = inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎",
                new BigDecimal("300"), "CASH", null).getId();
        var drug = seeds.anyDrug();
        var order = inpatientService.createOrders(admId,
                List.of(new InpatientService.OrderLine("DRUG", drug.getId(), 2, "口服", "bid", "1粒")), null).get(0);
        inpatientService.execute(order.getId(), null);
        entityManager.flush();
        // 直接取已执行医嘱的执行日，规避时区跨日抖动
        String date = jdbc.queryForObject(
                "select executed_at::date::text from inp_order where id = ?", String.class, order.getId());

        Map<String, Object> ds = inpatientController.printDailyFee(admId, date).getData();
        assertEquals("日清单打印P1", ds.get("patient_name"));
        assertNotNull(ds.get("admission_no"));
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) ds.get("rows");
        assertEquals(1, rows.size(), "当日应有一条已执行费用");
        BigDecimal expect = drug.getPrice().multiply(BigDecimal.valueOf(2));
        assertEquals(0, ((BigDecimal) ds.get("dayTotal")).compareTo(expect), "当日合计=单价*数量");
        assertEquals(0, ((BigDecimal) ds.get("depositTotal")).compareTo(new BigDecimal("300")));
        assertNotNull(ds.get("balance"));
    }

    /** 打印2：出院小结数据集含基本信息、诊断、诊疗经过（病历）与出院带药。 */
    @Test
    void dischargeSummaryPrintDatasetAssembled() {
        Long pid = newPatient("出院小结P2");
        Long bedId = freeBed();
        Long admId = inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎",
                new BigDecimal("500"), "CASH", null).getId();
        var drug = seeds.anyDrug();
        var order = inpatientService.createOrders(admId,
                List.of(new InpatientService.OrderLine("DRUG", drug.getId(), 1, "口服", "qd", "1粒")), null).get(0);
        inpatientService.execute(order.getId(), null);
        // 出院诊断 + 出院小结病历
        jdbc.update("update inp_admission set discharge_diag_icd = 'J18.9', discharge_diag_name = '社区获得性肺炎' where id = ?", admId);
        jdbc.update("""
                insert into inp_medical_record(admission_id, record_type, title, content)
                values (?, 'DISCHARGE', '出院小结', '经抗感染治疗好转，准予出院。出院医嘱：注意休息，一周后门诊复诊。')
                """, admId);
        entityManager.flush();

        Map<String, Object> ds = inpatientController.printDischargeSummary(admId).getData();
        assertEquals("肺炎", ds.get("admit_diag_name"));
        assertEquals("社区获得性肺炎", ds.get("discharge_diag_name"));
        @SuppressWarnings("unchecked")
        var records = (List<Map<String, Object>>) ds.get("records");
        assertTrue(records.stream().anyMatch(r -> "DISCHARGE".equals(r.get("record_type"))),
                "诊疗经过应含出院小结病历");
        @SuppressWarnings("unchecked")
        var meds = (List<Map<String, Object>>) ds.get("meds");
        assertEquals(1, meds.size(), "出院带药应含已执行药嘱");
    }
}
