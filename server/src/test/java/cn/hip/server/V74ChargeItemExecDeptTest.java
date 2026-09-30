package cn.hip.server;

import cn.hip.outpatient.entity.OutpDiagnosis;
import cn.hip.outpatient.entity.OutpEmr;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.platform.masterdata.web.MasterDataController;
import cn.hip.platform.masterdata.web.MasterDataController.ItemAttrReq;
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
 * v74 审阅修补：收费项目「执行科室」的产品内写入路径（1026★ 第二轮复核打回）。
 *
 * <p>背景：{@code md_charge_item.exec_dept_id} 自 V4 建列起种子全空，产品内没有任何写入路径
 * （收费项目页面无此字段、CSV 导入与 attrs 接口都不写），治疗单与导诊单的"前往科室"恒印"—"。
 * V43PrintDocsTest 之所以一直绿，是因为它直接 {@code jdbc.update("update md_charge_item set exec_dept_id = 1 ...")}
 * 绕过了产品写入路径——<b>测的是"读侧 join 写对了"，不是"用户能把这个值配进去"</b>。
 * 本用例补的是后者：全程只走 {@link MasterDataController} 的公开写入口，不直写 exec_dept_id，
 * 最后再印一张治疗单/导诊单验证端到端。
 *
 * <p>全部 {@code @Transactional}：本用例只断言落库值与返回码，不测回滚语义（方法论④）。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class V74ChargeItemExecDeptTest {

    @Autowired MasterDataController masterData;
    @Autowired PrintReportController printController;
    @Autowired PatientService patientService;
    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired cn.hip.outpatient.repository.OutpScheduleRepository scheduleRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;

    private Long newItem(String code, String category) {
        jdbc.update("""
                insert into md_charge_item(code, name, category, unit, price, enabled)
                values (?, ?, ?, '次', 30.00, true)
                """, code, "执行科室用例-" + code, category);
        return idOf(code);
    }

    private Long idOf(String code) {
        return jdbc.queryForObject("select id from md_charge_item where code = ?", Long.class, code);
    }

    private Long execDeptOf(Long itemId) {
        return jdbc.queryForObject("select exec_dept_id from md_charge_item where id = ?", Long.class, itemId);
    }

    private Long newDept(String code, boolean enabled) {
        jdbc.update("insert into sys_dept(name, code, type, sort_no, enabled) values (?, ?, 'MEDTECH', 99, ?)",
                "用例科室" + code, code, enabled);
        return jdbc.queryForObject("select id from sys_dept where code = ?", Long.class, code);
    }

    @Test
    void attrsWriteExecDeptAndKeepItWhenOmitted() {
        Long item = newItem("T74A1", "LAB");
        Long dept = newDept("T74D1", true);
        assertNull(execDeptOf(item), "前提：新建项目执行科室为空");

        assertEquals(0, masterData.updateChargeItemAttrs(item, new ItemAttrReq(null, null, dept, null)).getCode());
        assertEquals(dept, execDeptOf(item), "传 execDeptId 必须真落库");

        // 向后兼容：旧的两参调用（只改自费）不得顺手清掉执行科室
        assertEquals(0, masterData.updateChargeItemAttrs(item, new ItemAttrReq(null, true)).getCode());
        assertEquals(dept, execDeptOf(item), "未传 execDeptId 时保持原值");
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "select self_pay from md_charge_item where id = ?", Boolean.class, item));

        // 显式清空
        assertEquals(0, masterData.updateChargeItemAttrs(item, new ItemAttrReq(null, null, null, true)).getCode());
        assertNull(execDeptOf(item), "clearExecDept=true 必须能清空");
    }

    @Test
    void attrsRejectUnknownOrDisabledOrContradictoryDeptWithoutSideEffect() {
        Long item = newItem("T74A2", "EXAM");
        Long dept = newDept("T74D2", true);
        Long off = newDept("T74D3", false);
        assertEquals(0, masterData.updateChargeItemAttrs(item, new ItemAttrReq(null, null, dept, null)).getCode());

        assertEquals(4000, masterData.updateChargeItemAttrs(item,
                new ItemAttrReq(null, true, 987654321L, null)).getCode(), "科室不存在");
        assertEquals(4000, masterData.updateChargeItemAttrs(item,
                new ItemAttrReq(null, true, off, null)).getCode(), "已停用科室不能作执行科室");
        assertEquals(4000, masterData.updateChargeItemAttrs(item,
                new ItemAttrReq(null, true, dept, true)).getCode(), "设置与清空同传自相矛盾");

        assertEquals(dept, execDeptOf(item), "被拒绝的请求不得改动执行科室");
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "select self_pay from md_charge_item where id = ?", Boolean.class, item),
                "被拒绝的请求不得顺带写入自费标记（校验前置于任何写）");
        // 项目不存在仍是既有的 4864，不被新校验吞掉
        assertEquals(4864, masterData.updateChargeItemAttrs(-1L, new ItemAttrReq(null, null, dept, null)).getCode());
    }

    @Test
    void drugAttrsRefuseExecDeptInsteadOfSilentlyIgnoringIt() {
        Long drug = jdbc.queryForObject("select id from md_drug order by id limit 1", Long.class);
        assertEquals(4000, masterData.updateDrugAttrs(drug, new ItemAttrReq(null, null, 1L, null)).getCode());
        assertEquals(4000, masterData.updateDrugAttrs(drug, new ItemAttrReq(null, null, null, true)).getCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void csvImportResolvesExecDeptCodeSoftly() {
        Long dept = newDept("T74D4", true);
        String csv = "code,name,category,unit,price,fee_category_code,self_pay,exec_dept_code\n"
                + "T74C1,导入项目甲,LAB,次,10.00,,,T74D4\n"
                + "T74C2,导入项目乙,EXAM,次,20.00,,,NO_SUCH_DEPT\n"
                + "T74C3,导入项目丙,TREAT,次,30.00\n";
        var data = (Map<String, Object>) masterData.importChargeItems(csv).getData();
        assertEquals(3, data.get("imported"), "软校验：未知科室码的行照常导入");
        assertEquals(1, data.get("errorCount"));
        assertTrue(((List<String>) data.get("errors")).get(0).contains("NO_SUCH_DEPT"), "行级错误要点名科室码");

        assertEquals(dept, execDeptOf(idOf("T74C1")));
        assertNull(execDeptOf(idOf("T74C2")), "未知科室码不挂科室");
        assertNull(execDeptOf(idOf("T74C3")), "旧的 5 列 CSV 不受影响");

        // 二次导入同一项目、不带科室列 → 保持原科室（不得被"空列"清掉）；带新科室码 → 覆盖
        masterData.importChargeItems("T74C1,导入项目甲,LAB,次,11.00\n");
        assertEquals(dept, execDeptOf(idOf("T74C1")), "空列 = 保持原值");
        Long dept2 = newDept("T74D5", true);
        masterData.importChargeItems("T74C1,导入项目甲,LAB,次,11.00,,,T74D5\n");
        assertEquals(dept2, execDeptOf(idOf("T74C1")), "带有效科室码则覆盖");
    }

    /** 端到端：只走产品写入口配好执行科室，开单后印治疗单/导诊单，"前往科室"是科室名而非空 */
    @Test
    void printedSheetsCarryExecDeptConfiguredThroughProductPath() {
        Long treat = newItem("T74P1", "TREAT");
        Long exam = newItem("T74P2", "EXAM");
        Long unset = newItem("T74P3", "LAB");
        Long dept = newDept("T74D6", true);
        String deptName = jdbc.queryForObject("select name from sys_dept where id = ?", String.class, dept);
        masterData.updateChargeItemAttrs(treat, new ItemAttrReq(null, null, dept, null));
        masterData.updateChargeItemAttrs(exam, new ItemAttrReq(null, null, dept, null));

        Long doctorId = jdbc.queryForObject("select id from sys_user where username = 'admin'", Long.class);
        Patient p = new Patient();
        p.setName("执行科室" + System.nanoTime() % 100000);
        p.setSex("M");
        p.setBirthDate(LocalDate.of(1980, 5, 20));
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
        OutpDiagnosis d = new OutpDiagnosis();
        d.setIcdCode("J00");
        d.setIcdName("急性上呼吸道感染");
        doctorStationService.saveEmr(rid, emr, List.of(d), doctorId);
        doctorStationService.createOrders(rid, List.of(
                new OrderLine("TREAT", treat, 1, null, null, null, null),
                new OrderLine("EXAM", exam, 1, null, null, null, null),
                new OrderLine("LAB", unset, 1, null, null, null, null)), doctorId);
        em.flush();

        @SuppressWarnings("unchecked")
        var treatRows = (List<Map<String, Object>>) printController.clinicalDoc("treat-sheet", rid, null)
                .getData().get("rows");
        assertEquals(deptName, treatRows.get(0).get("exec_dept_name"), "治疗单前往科室 = 产品路径配置的科室名");

        @SuppressWarnings("unchecked")
        var guideRows = (List<Map<String, Object>>) printController.clinicalDoc("guide-sheet", rid, null)
                .getData().get("rows");
        var byType = guideRows.stream().collect(java.util.stream.Collectors.toMap(
                r -> String.valueOf(r.get("order_type")), r -> r));
        assertEquals(deptName, byType.get("TREAT").get("exec_dept_name"));
        assertEquals(deptName, byType.get("EXAM").get("exec_dept_name"));
        assertNull(byType.get("LAB").get("exec_dept_name"), "未配置的项目仍为空（前端印 —），对照组");
    }
}
