package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.insurance.web.InsuranceController;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.server.web.StatsController;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v41 管理决策报表回归：科室月度经营报表（+医生工作量）与医保基金使用监测。
 *
 * <p>脏库对策（Phase113FinanceTest 方法论⑦）：科室月报是**按科室聚合**的绝对数，
 * 复用既有科室会把全库历史收费算进断言。故本类为每次运行新建一个**当次唯一的科室**，
 * 该科室在全表无任何历史行 → 绝对断言恒真、与残留无关。基金监测是全院按月聚合、
 * 无法隔离，改用「同一月份行的前后差值」断言，同样与残留无关。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = {"ADMIN", "QUALITY", "CASHIER"})
class V41MgmtReportTest {

    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired StatsController statsController;
    @Autowired InsuranceController insuranceController;
    @Autowired InpatientService inpatientService;
    @Autowired ChargeService chargeService;
    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired PatientService patientService;
    @Autowired cn.hip.outpatient.repository.OutpScheduleRepository scheduleRepository;
    @Autowired cn.hip.platform.core.service.ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private final String month = YearMonth.from(BusinessDates.today()).toString();

    // ---------------- 夹具 ----------------

    private Long newPatient(String name) {
        return newPatient(name, null);
    }

    /** 参保类型在建档时就写入（同 InsuranceSplitTest）：建档后再 jdbc 改，JPA 侧仍持旧快照 */
    private Long newPatient(String name, String insuranceType) {
        Patient p = new Patient();
        p.setName(name);
        p.setSex("M");
        if (insuranceType != null) {
            p.setInsuranceType(insuranceType);
        }
        return patientService.register(p).getId();
    }

    /** 当次唯一的科室：全表零历史行，科室月报的绝对断言才成立 */
    private Long dedicatedDept(String label) {
        String code = "V41" + label + System.nanoTime() % 100000000L;
        return jdbc.queryForObject("""
                insert into sys_dept(parent_id, name, code, type, sort_no)
                values (null, ?, ?, 'CLINIC', 999) returning id
                """, Long.class, "V41经营科" + label, code);
    }

    private Long dedicatedDoctor(String label) {
        String username = "v41doc" + label + System.nanoTime() % 100000000L;
        return jdbc.queryForObject("""
                insert into sys_user(username, password, real_name, enabled)
                values (?, 'x', ?, true) returning id
                """, Long.class, username, "V41医生" + label);
    }

    private Long freeBed() {
        return jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
    }

    /** 指定科室/医生的挂号 + 一条药嘱（挂号费 0，收费单金额即药费，断言好算） */
    private Long regWithOrder(Long patientId, Long deptId, Long doctorId) {
        var sch = new cn.hip.outpatient.entity.OutpSchedule();
        sch.setDeptId(deptId);
        sch.setDoctorId(doctorId);
        sch.setScheduleDate(BusinessDates.today());
        sch.setFee(BigDecimal.ZERO);
        sch.setCapacity(9);
        sch = scheduleRepository.save(sch);
        Long regId = registrationService.register(patientId, sch.getId()).getId();
        doctorStationService.startVisit(regId, null);
        // 开单医生传 null（同 Phase113FinanceTest）：医生工作量按**挂号医生**归集（r.doctor_id），
        // 与医嘱行上的开单人无关，传 null 可避开抗菌药分级授权等与本用例无关的守卫
        doctorStationService.createOrders(regId,
                List.of(new DoctorStationService.OrderLine("DRUG", seeds.anyDrug().getId(), 1, "口服", "qd", "1粒", null)),
                null);
        entityManager.flush();
        return regId;
    }

    private void executedInpOrder(Long admId, int qty) {
        var o = inpatientService.createOrders(admId,
                List.of(new InpatientService.OrderLine("DRUG", seeds.anyDrug().getId(), qty, "口服", "qd", "1粒")),
                null).get(0);
        inpatientService.execute(o.getId(), null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deptRow(Long deptId) {
        var data = statsController.deptMonthly(month).getData();
        return ((List<Map<String, Object>>) data.get("depts")).stream()
                .filter(r -> deptId.equals(((Number) r.get("dept_id")).longValue()))
                .findFirst().orElseThrow(() -> new AssertionError("科室月报应出现该科室行"));
    }

    private static BigDecimal big(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(v));
    }

    // ==================== ① 科室月报：INTERIM 不得重复计费 ====================

    /**
     * 本类最关键的断言：一次住院里既有 INTERIM 中间结算又有 FINAL 出院结算时，
     * 科室月报的住院收入必须**只等于 FINAL 的金额**。
     *
     * <p>为什么这能证明"不重复计费"：中间结算的金额恒为出院总额的子集
     * （discharge 按医嘱台账现算，见 V90），本例里 INTERIM=1×price、FINAL=3×price。
     * 若报表漏了 settle_type='FINAL' 过滤，聚合值会是 4×price（=1+3），
     * 出院人次也会变成 2。故断言「= 3×price 且出院人次 = 1」既证明过滤生效，
     * 又能在过滤被删掉时必然失败。同时先断言库里确实存在这张 INTERIM 行——
     * 否则"金额对得上"可能只是因为中间结算根本没造出来。
     */
    @Test
    void deptMonthlyCountsFinalSettlementOnly() {
        Long deptId = dedicatedDept("住院");
        Long pid = newPatient("V41住院经营");
        BigDecimal price = seeds.anyDrug().getPrice();

        Long admId = inpatientService.admit(pid, deptId, freeBed(), null, "J18.9", "肺炎",
                new BigDecimal("5000"), "CASH", null).getId();
        executedInpOrder(admId, 1);
        entityManager.flush();
        var interim = inpatientService.interimSettle(admId, null, "CASH");
        entityManager.flush();
        assertEquals("INTERIM", interim.getSettleType());
        assertEquals(0, interim.getTotalAmount().compareTo(price), "中间结算结掉首笔已发生费用");

        executedInpOrder(admId, 2);
        entityManager.flush();
        var fin = inpatientService.discharge(admId, null, "CASH");
        entityManager.flush();
        assertEquals("FINAL", fin.getSettleType());
        BigDecimal finalTotal = price.multiply(BigDecimal.valueOf(3));
        assertEquals(0, fin.getTotalAmount().compareTo(finalTotal), "出院总额=全部已执行医嘱 3×price");

        // 前提确认：库里确实有一张 INTERIM 行（否则下面的断言没有区分度）
        assertEquals(1, (int) jdbc.queryForObject(
                "select count(*) from inp_settlement where admission_id = ? and settle_type = 'INTERIM'",
                Integer.class, admId), "本例必须存在一张中间结算行");
        assertEquals(2, (int) jdbc.queryForObject(
                "select count(*) from inp_settlement where admission_id = ?", Integer.class, admId),
                "该次住院共两张结算行（INTERIM + FINAL）");

        var row = deptRow(deptId);
        assertEquals(1L, ((Number) row.get("inp_discharges")).longValue(),
                "出院人次只数 FINAL；把 INTERIM 计入会变成 2");
        assertEquals(0, big(row.get("inp_revenue")).compareTo(finalTotal),
                "住院收入只认 FINAL（3×price）；若把 INTERIM 一起 sum 会是 4×price——同一笔费用计两次");
        assertTrue(big(row.get("inp_revenue"))
                        .compareTo(interim.getTotalAmount().add(fin.getTotalAmount())) < 0,
                "住院收入必须严格小于 Σ(中间+出院)，即中间结算未被叠加");
        assertEquals(0, big(row.get("inp_avg_cost")).compareTo(finalTotal), "住院均次费用=3×price/1");

        // CSV 与 JSON 同口径同 SQL：同样只出 FINAL 金额
        String csv = statsController.deptMonthlyCsv(month);
        String deptName = String.valueOf(row.get("dept_name"));
        String line = csv.lines().filter(l -> l.contains(deptName)).findFirst()
                .orElseThrow(() -> new AssertionError("CSV 应含该科室行"));
        assertTrue(line.contains(finalTotal.toPlainString()), "CSV 住院收入应为 FINAL 金额：" + line);
    }

    /** 门诊侧口径 + 医生工作量：收入/人次/均次费用与该科室当月唯一一张收费单一致 */
    @Test
    void deptMonthlyOutpatientAndDoctorWorkload() {
        Long deptId = dedicatedDept("门诊");
        Long doctorId = dedicatedDoctor("门诊");
        Long pid = newPatient("V41门诊经营");
        Long regId = regWithOrder(pid, deptId, doctorId);
        var charge = chargeService.settle(regId, "CASH", null);
        entityManager.flush();

        var row = deptRow(deptId);
        assertEquals(1L, ((Number) row.get("outp_visits")).longValue(), "门诊人次=1");
        assertEquals(0, big(row.get("outp_revenue")).compareTo(charge.getTotalAmount()), "门诊收入=收费单金额");
        assertEquals(0, big(row.get("outp_avg_cost")).compareTo(charge.getTotalAmount()), "均次费用=收入/人次");
        assertEquals(0, big(row.get("total_revenue")).compareTo(charge.getTotalAmount()),
                "合计收入=门诊+住院（本科室无住院）");

        @SuppressWarnings("unchecked")
        var byDoctor = (List<Map<String, Object>>) statsController.deptMonthly(month).getData().get("byDoctor");
        var doc = byDoctor.stream()
                .filter(d -> doctorId.equals(d.get("doctor_id")))
                .findFirst().orElseThrow(() -> new AssertionError("医生工作量应出现该医生"));
        assertEquals(1L, ((Number) doc.get("visits")).longValue(), "接诊量=1");
        assertEquals(0, big(doc.get("order_amount")).compareTo(charge.getTotalAmount()), "处方金额=已收费医嘱合计");
    }

    /** 月份半开区间：上月查询看不到本月的收入（口径不串月） */
    @Test
    void deptMonthlyIsScopedToTheRequestedMonth() {
        Long deptId = dedicatedDept("月份");
        Long pid = newPatient("V41月份口径");
        chargeService.settle(regWithOrder(pid, deptId, null), "CASH", null);
        entityManager.flush();

        String lastMonth = YearMonth.from(BusinessDates.today()).minusMonths(1).toString();
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) statsController.deptMonthly(lastMonth).getData().get("depts");
        assertTrue(rows.stream().noneMatch(r -> deptId.equals(((Number) r.get("dept_id")).longValue())),
                "本月的收费不得出现在上月报表里");
        assertEquals(lastMonth, statsController.deptMonthly(lastMonth).getData().get("month"));
    }

    // ==================== ② 医保基金监测：冲销行必须排除 ====================

    /** 取当月 OUTP 那一行的 [笔数, 结算总额, 统筹支付]；无该行按 0 */
    @SuppressWarnings("unchecked")
    private BigDecimal[] outpMonthRow() {
        var monthly = (List<Map<String, Object>>) insuranceController.fundMonitor().getData().get("monthly");
        return monthly.stream()
                .filter(r -> month.equals(r.get("month")) && "OUTP".equals(r.get("biz_type")))
                .findFirst()
                .map(r -> new BigDecimal[]{big(r.get("bills")), big(r.get("total")), big(r.get("fund_pay"))})
                .orElse(new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
    }

    /**
     * 冲销（退费）后的分割行必须被基金监测排除。
     *
     * <p>脏库下无法对全院月度聚合做绝对断言，故取**同一行的前后差值**：
     * 结算后笔数/总额/统筹各增加恰好一笔的量；退费冲销后三项**回到结算前的值**。
     * 若查询漏了 `not reversed`，退费后三项仍停在增加后的水平——已经退还医保的钱
     * 会继续被算成基金支出，占比虚高。
     */
    @Test
    void fundMonitorExcludesReversedSplits() {
        Long pid = newPatient("V41医保基金", "YB_STAFF");
        // 药品对照成甲类：否则未对照按丙类自费，统筹支付恒为 0，"占比排除冲销"就失去区分度
        var drug = seeds.anyDrug();
        jdbc.update("""
                insert into yb_catalog_map(item_type, item_code, item_name, yb_code, charge_class, self_ratio)
                values ('DRUG', ?, ?, ?, 'A', 0)
                on conflict (item_type, item_code) do update set charge_class = 'A', self_ratio = 0
                """, drug.getCode(), drug.getName(), "V41-" + drug.getCode());

        BigDecimal[] before = outpMonthRow();

        Long regId = regWithOrder(pid, dedicatedDept("医保"), null);
        var charge = chargeService.settle(regId, "YB", null);
        entityManager.flush();
        var split = jdbc.queryForMap(
                "select class_a, fund_pay from yb_settle_split where charge_no = ?", charge.getChargeNo());
        assertTrue(big(split.get("class_a")).signum() > 0, "药品应按甲类分割（对照未生效则统筹恒为 0）");
        BigDecimal fundPay = big(split.get("fund_pay"));
        assertTrue(fundPay.signum() > 0, "职工医保 + 甲类药应有统筹支付，否则本用例无区分度");

        BigDecimal[] mid = outpMonthRow();
        assertEquals(0, mid[0].subtract(before[0]).compareTo(BigDecimal.ONE), "新结算计入 1 笔");
        assertEquals(0, mid[1].subtract(before[1]).compareTo(charge.getTotalAmount()), "结算总额随之增加");
        assertEquals(0, mid[2].subtract(before[2]).compareTo(fundPay), "统筹支付随之增加");

        chargeService.refund(charge.getId(), null);
        entityManager.flush();
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "select reversed from yb_settle_split where charge_no = ?", Boolean.class, charge.getChargeNo()),
                "退费后分割行应被打上冲销标记");

        BigDecimal[] after = outpMonthRow();
        assertEquals(0, after[0].compareTo(before[0]), "冲销行不得再计入笔数");
        assertEquals(0, after[1].compareTo(before[1]), "冲销行不得再计入结算总额");
        assertEquals(0, after[2].compareTo(before[2]), "冲销行不得再计入统筹支付——否则已退还医保的钱仍算基金支出");
    }

    // ==================== ③ 封顶线：0 = 未启用，不得按 0 判全员超限 ====================

    private void setCap(String key, String value) {
        jdbc.update("""
                insert into sys_config(cfg_key, cfg_value, remark) values (?, ?, 'v41 测试')
                on conflict (cfg_key) do update set cfg_value = excluded.cfg_value
                """, key, value);
        configReader.evict(key);   // 30 秒缓存必须立刻失效，否则读到旧值
    }

    @Test
    void capAlertIsNullWhenCapDisabled() {
        try {
            setCap("yb_cap_staff", "0");
            setCap("yb_cap_resident", "0");
            var data = insuranceController.fundMonitor().getData();
            assertNull(data.get("capAlerts"), "cap=0 是未启用；按 0 算会让全员 fund_used>=0 命中，报表全是假警报");
            assertTrue(String.valueOf(data.get("capAlertNote")).contains("未启用"), "须注明未启用");
            @SuppressWarnings("unchecked")
            var caps = (Map<String, Object>) data.get("caps");
            assertNull(caps.get("staff"));
            assertNull(caps.get("resident"));
        } finally {
            configReader.evictAll();   // 事务回滚只还原库值，缓存须显式清，避免污染同 JVM 后续用例
        }
    }

    @Test
    void capAlertListsPatientsNearCapWhenEnabled() {
        Long pid = newPatient("V41封顶线", "YB_STAFF");
        int year = BusinessDates.today().getYear();
        jdbc.update("""
                insert into yb_patient_annual(patient_id, year, deductible_used, fund_used)
                values (?, ?, 0, 9500)
                on conflict (patient_id, year) do update set fund_used = 9500
                """, pid, year);
        try {
            setCap("yb_cap_staff", "10000");
            setCap("yb_cap_resident", "0");
            var data = insuranceController.fundMonitor().getData();
            @SuppressWarnings("unchecked")
            var alerts = (List<Map<String, Object>>) data.get("capAlerts");
            assertNotNull(alerts, "启用封顶线后应给出预警名单");
            var hit = alerts.stream().filter(a -> pid.equals(a.get("patient_id"))).findFirst()
                    .orElseThrow(() -> new AssertionError("年度统筹 9500 已达封顶线 10000 的 90%，应进预警名单"));
            assertEquals(0, big(hit.get("used_ratio")).compareTo(new BigDecimal("95.0")));
            assertEquals(0, big(hit.get("cap")).compareTo(new BigDecimal("10000")));
            // 居民封顶线仍为 0 = 未启用：其参保人不应被按 0 判进名单
            assertTrue(alerts.stream().noneMatch(a -> "YB_RESIDENT".equals(a.get("insurance_type"))),
                    "未启用封顶线的险种不得出现在预警名单里");
            @SuppressWarnings("unchecked")
            var caps = (Map<String, Object>) data.get("caps");
            assertEquals(0, big(caps.get("staff")).compareTo(new BigDecimal("10000")));
            assertNull(caps.get("resident"), "未启用的险种封顶线返回 null");
        } finally {
            configReader.evictAll();
        }
    }
}
