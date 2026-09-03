package cn.hip.server;

import cn.hip.outpatient.entity.OutpDiagnosis;
import cn.hip.outpatient.entity.OutpEmr;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.server.web.PrintReportController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v43 车道B：五种日常单据打印数据集（偏离表 1026★——处方笺/检验申请单/检查申请单/治疗单/导诊单，
 * 全量核对时全仓零命中而应答答"已实现"）。
 *
 * <p>断言口径：① 五种单据返回体的页眉（患者/科室/医师）与明细行齐全；
 * ② 单据数据不存在返 4893（挂号不存在、以及本次就诊没有该类医嘱两条路径）；
 * ③ 类型不支持返 4892。全部端点只读，本测试不断言任何写路径行为。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class V43PrintDocsTest {

    @Autowired PrintReportController printController;
    @Autowired PatientService patientService;
    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired cn.hip.outpatient.repository.OutpScheduleRepository scheduleRepository;
    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;

    /** 一次完整就诊：挂号 → 接诊 → 写病历下诊断 → 开四类医嘱（药/检验/检查/治疗） */
    private Long visitWithAllOrderTypes() {
        Long doctorId = jdbc.queryForObject(
                "select id from sys_user where username = 'admin'", Long.class);
        Patient p = new Patient();
        p.setName("单据" + System.nanoTime() % 100000);
        p.setSex("M");
        p.setBirthDate(LocalDate.of(1980, 5, 20));
        p.setAllergyHistory("青霉素过敏");
        Long pid = patientService.register(p).getId();

        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        Long rid = registrationService.register(pid, s.getId()).getId();
        doctorStationService.startVisit(rid, doctorId);

        OutpEmr emr = new OutpEmr();
        emr.setChiefComplaint("咳嗽三天");
        emr.setPresentIllness("三天前受凉后出现咳嗽，无发热");
        emr.setPhysicalExam("双肺呼吸音粗");
        OutpDiagnosis d = new OutpDiagnosis();
        d.setIcdCode("J00");
        d.setIcdName("急性上呼吸道感染");
        doctorStationService.saveEmr(rid, emr, List.of(d), doctorId);

        Long drugId = seeds.drug("单据测试药").getId();
        Long labId = seeds.chargeItem("单据测试检验", "LAB").getId();
        Long examId = seeds.chargeItem("单据测试检查", "EXAM").getId();
        Long treatId = seeds.chargeItem("单据测试治疗", "TREAT").getId();
        // 执行科室是申请单/导诊单的核心信息（"这张单去哪儿做"），给测试项目挂上便于断言
        jdbc.update("update md_charge_item set exec_dept_id = 1 where id in (?,?,?)", labId, examId, treatId);

        doctorStationService.createOrders(rid, List.of(
                new OrderLine("DRUG", drugId, 2, "口服", "每日三次", "1粒", 3),
                new OrderLine("LAB", labId, 1, null, null, null, null),
                new OrderLine("EXAM", examId, 1, null, null, null, null),
                new OrderLine("TREAT", treatId, 1, null, null, null, null)), doctorId);
        em.flush();
        return rid;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(Map<String, Object> doc) {
        return (List<Map<String, Object>>) doc.get("rows");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> groupsOf(Map<String, Object> doc) {
        return (List<Map<String, Object>>) doc.get("groups");
    }

    /** 五种单据的共用页眉：患者 / 科室 / 医师 / 号序 / 诊断——纸面法定必印项 */
    private void assertHeader(Map<String, Object> doc, String expectTitle) {
        assertEquals(expectTitle, doc.get("docTitle"));
        assertNotNull(doc.get("patient_name"), "缺患者姓名");
        assertNotNull(doc.get("patient_no"), "缺门诊号");
        assertNotNull(doc.get("sex"), "缺性别");
        assertNotNull(doc.get("age"), "缺年龄（由出生日期按业务日期算）");
        assertNotNull(doc.get("dept_name"), "缺就诊科室");
        assertNotNull(doc.get("doctor_name"), "缺接诊医师");
        assertNotNull(doc.get("reg_no"), "缺号序");
        assertNotNull(doc.get("visit_date"), "缺就诊日期");
        assertFalse(((List<?>) doc.get("diagnoses")).isEmpty(), "缺临床诊断");
    }

    @Test
    void prescriptionCarriesRpLinesAndUsage() {
        Long rid = visitWithAllOrderTypes();
        var r = printController.clinicalDoc("prescription", rid, null);
        assertEquals(0, r.getCode());
        var doc = r.getData();
        assertHeader(doc, "处方笺");
        assertEquals("青霉素过敏", doc.get("allergy_history"), "处方笺须印过敏史");

        var rows = rowsOf(doc);
        assertEquals(1, rows.size(), "本次就诊只开了一条药品行");
        var line = rows.get(0);
        assertEquals("DRUG", line.get("order_type"));
        assertNotNull(line.get("item_name"));
        assertNotNull(line.get("group_no"), "处方号即 group_no");
        assertEquals("口服", line.get("usage_route"));
        assertEquals("每日三次", line.get("frequency"));
        assertEquals("1粒", line.get("dose_per_time"));
        assertEquals(3, ((Number) line.get("days")).intValue());
        assertNotNull(line.get("unit"), "×数量单位要印");

        var groups = groupsOf(doc);
        assertEquals(1, groups.size(), "一个处方号一张纸");
        assertEquals(line.get("group_no"), groups.get(0).get("groupNo"));
        assertNotNull(groups.get(0).get("total"), "处方金额合计要印");

        // groupNo 过滤：只打其中一张
        assertEquals(0, printController.clinicalDoc(
                "prescription", rid, String.valueOf(line.get("group_no"))).getCode());
        assertEquals(4893, printController.clinicalDoc("prescription", rid, "根本没有的处方号").getCode(),
                "指定了不存在的处方号应按单据不存在处理");
    }

    @Test
    void labRequestCarriesRequestNoAndSpecimenColumns() {
        Long rid = visitWithAllOrderTypes();
        var doc = printController.clinicalDoc("lab-request", rid, null).getData();
        assertHeader(doc, "检验申请单");
        // 病史摘要（申请单要让医技科室看到"为什么做"）
        assertEquals("咳嗽三天", ((Map<?, ?>) doc.get("emr")).get("chief_complaint"));

        var rows = rowsOf(doc);
        assertEquals(1, rows.size());
        var line = rows.get(0);
        assertEquals("LAB", line.get("order_type"));
        assertNotNull(line.get("group_no"), "申请单号即 group_no");
        assertNotNull(line.get("exec_dept_name"), "缺执行科室");
        // 标本列：未采样时 lis_sample 无行，字段为 null 属正常（纸面留手填位）
        assertTrue(line.containsKey("sample_barcode"), "标本条码列须存在（未采样为 null）");
        assertTrue(line.containsKey("sample_status"), "标本状态列须存在");
        assertEquals(1, groupsOf(doc).size());
    }

    @Test
    void examRequestCarriesExamItemsAndExecDept() {
        Long rid = visitWithAllOrderTypes();
        var doc = printController.clinicalDoc("exam-request", rid, null).getData();
        assertHeader(doc, "检查申请单");
        assertEquals("双肺呼吸音粗", ((Map<?, ?>) doc.get("emr")).get("physical_exam"),
                "检查申请单要带体格检查");

        var rows = rowsOf(doc);
        assertEquals(1, rows.size());
        assertEquals("EXAM", rows.get(0).get("order_type"));
        assertNotNull(rows.get(0).get("item_name"), "检查项目名本身即含部位");
        assertNotNull(rows.get(0).get("exec_dept_name"), "缺执行科室");
        assertNotNull(rows.get(0).get("group_no"), "缺申请单号");
    }

    @Test
    void treatSheetCarriesTreatItemsAndExecDept() {
        Long rid = visitWithAllOrderTypes();
        var doc = printController.clinicalDoc("treat-sheet", rid, null).getData();
        assertHeader(doc, "治疗单");

        var rows = rowsOf(doc);
        assertEquals(1, rows.size());
        assertEquals("TREAT", rows.get(0).get("order_type"));
        assertNotNull(rows.get(0).get("exec_dept_name"), "治疗单必须写明执行科室");
        assertNotNull(rows.get(0).get("qty"));
        assertNotNull(rows.get(0).get("unit"));
    }

    @Test
    void guideSheetListsAllPendingItemsAcrossTypes() {
        Long rid = visitWithAllOrderTypes();
        var doc = printController.clinicalDoc("guide-sheet", rid, null).getData();
        assertHeader(doc, "导诊单");
        assertNull(doc.get("groups"), "导诊单对应整次就诊，没有单据号分组");

        var rows = rowsOf(doc);
        assertEquals(4, rows.size(), "四类医嘱都还没执行，导诊单应全列出来");
        assertEquals(List.of("DRUG", "EXAM", "LAB", "TREAT"),
                rows.stream().map(r -> String.valueOf(r.get("order_type"))).sorted().toList());
        assertTrue(rows.stream().allMatch(r -> "CREATED".equals(r.get("status")) || "CHARGED".equals(r.get("status"))),
                "导诊单只列待缴费/待执行的项目");
        assertTrue(rows.stream().filter(r -> !"DRUG".equals(r.get("order_type")))
                        .allMatch(r -> r.get("exec_dept_name") != null),
                "医技项目必须写明前往科室，否则患者不知道去哪做");
    }

    @Test
    void missingDocDataReturns4893() {
        // ① 挂号根本不存在
        assertEquals(4893, printController.clinicalDoc("prescription", 99999999L, null).getCode());
        assertEquals(4893, printController.clinicalDoc("guide-sheet", 99999999L, null).getCode());

        // ② 挂号在，但本次就诊没有该类医嘱：只开检验，去打处方笺/检查申请单/治疗单
        Long doctorId = jdbc.queryForObject("select id from sys_user where username = 'admin'", Long.class);
        Patient p = new Patient();
        p.setName("只检验" + System.nanoTime() % 100000);
        p.setSex("F");
        Long pid = patientService.register(p).getId();
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        Long rid = registrationService.register(pid, s.getId()).getId();
        doctorStationService.startVisit(rid, doctorId);
        doctorStationService.createOrders(rid, List.of(
                new OrderLine("LAB", seeds.chargeItem("单据测试检验", "LAB").getId(), 1, null, null, null, null)),
                doctorId);
        em.flush();

        assertEquals(4893, printController.clinicalDoc("prescription", rid, null).getCode(), "无药品行不应出处方笺");
        assertEquals(4893, printController.clinicalDoc("exam-request", rid, null).getCode());
        assertEquals(4893, printController.clinicalDoc("treat-sheet", rid, null).getCode());
        assertEquals(0, printController.clinicalDoc("lab-request", rid, null).getCode());
        // 导诊单：只挂号无待办也照样能出（患者要拿着找诊室），不返 4893
        assertEquals(0, printController.clinicalDoc("guide-sheet", rid, null).getCode());
    }

    @Test
    void unsupportedDocTypeReturns4892() {
        Long rid = visitWithAllOrderTypes();
        assertEquals(4892, printController.clinicalDoc("nope", rid, null).getCode());
        assertEquals(4892, printController.clinicalDoc("charge", rid, null).getCode(),
                "既有票据类型不在本端点白名单内");
        assertEquals(4892, printController.clinicalDoc("temp-sheet", rid, null).getCode());
        // 白名单内五种全部放行
        for (String t : List.of("prescription", "lab-request", "exam-request", "treat-sheet", "guide-sheet")) {
            assertNotEquals(4892, printController.clinicalDoc(t, rid, null).getCode(), t + " 应属受支持类型");
        }
    }
}
