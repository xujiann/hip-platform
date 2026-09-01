package cn.hip.server;

import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v41 收费员班结缴款单回归。
 *
 * <p>本类的断言全部建立在**当次事务内新建的专属唯一账号**上（Phase113FinanceTest 的教训）：
 * 班结 preview 与 reconciliation 都是全表按「日期 + 操作员」聚合，若复用全局 admin，
 * 脏 hip_test 里 admin 名下的历史收退费会被算进来，绝对金额断言在开发机上假红。
 * 新账号在 outp_charge 上无任何历史行 → 金额恒等于本测试造的那几笔。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class V41ShiftCloseTest {

    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired ChargeService chargeService;
    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired PatientService patientService;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired cn.hip.outpatient.repository.OutpScheduleRepository scheduleRepository;
    @Autowired cn.hip.server.web.FinanceController finance;

    private static final String TODAY = cn.hip.platform.core.config.BusinessDates.today().toString();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** 建一个当次专属账号（用户名带随机后缀，避免与残留数据/并行测试撞名） */
    private Long newUser(String username, String realName) {
        jdbc.update("""
                insert into sys_user(username, password, real_name, enabled)
                values (?, 'x', ?, true) on conflict (username) do nothing
                """, username, realName);
        return jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
    }

    private Authentication actAs(String username, String... roles) {
        var auth = new UsernamePasswordAuthenticationToken(username, null,
                java.util.Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
        SecurityContextHolder.getContext().setAuthentication(auth);
        return auth;
    }

    private Long newPatient(String name) {
        Patient p = new Patient();
        p.setName(name);
        p.setSex("M");
        return patientService.register(p).getId();
    }

    /** 挂号 + 开一条药嘱（挂号费 0，结算额=药品价），返回 registrationId */
    private Long regWithOrder(Long patientId) {
        var sch = new cn.hip.outpatient.entity.OutpSchedule();
        sch.setDeptId(1L);
        sch.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
        sch.setFee(BigDecimal.ZERO);
        sch.setCapacity(9);
        sch = scheduleRepository.save(sch);
        Long regId = registrationService.register(patientId, sch.getId()).getId();
        doctorStationService.startVisit(regId, null);
        Long itemId = seeds.anyDrug().getId();
        doctorStationService.createOrders(regId,
                List.of(new DoctorStationService.OrderLine("DRUG", itemId, 1, "口服", "qd", "1粒", null)), null);
        entityManager.flush();
        return regId;
    }

    /** 用指定收款员收一笔费，返回结算单 */
    private cn.hip.outpatient.entity.OutpCharge settleBy(Long cashierId, String patientName) {
        var charge = chargeService.settle(regWithOrder(newPatient(patientName)), "CASH", cashierId);
        entityManager.flush();
        return charge;
    }

    private static BigDecimal num(Object v) {
        return v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v));
    }

    /**
     * 主链路：preview 只含本人且与 reconciliation 同口径 → 提交（差额正确）→ 重复提交被拒
     * → ADMIN 确认 → 重复确认被拒。
     */
    @Test
    @SuppressWarnings("unchecked")
    void previewMatchesReconciliationThenSubmitAndConfirm() {
        Long uidA = newUser("cashierV41a", "班结收费员甲V41");
        Long uidB = newUser("cashierV41b", "班结收费员乙V41");
        Long uidFin = newUser("finadminV41", "班结财务V41");

        // 甲：两笔收款，其中一笔当日由甲本人退费 → 净额 = 一笔药费
        var a1 = settleBy(uidA, "班结甲一V41");
        var a2 = settleBy(uidA, "班结甲二V41");
        chargeService.refund(a2.getId(), uidA);
        entityManager.flush();
        // 乙：一笔收款（甲的 preview 绝不能把它算进来）
        var b1 = settleBy(uidB, "班结乙一V41");

        BigDecimal drugFee = a1.getTotalAmount();
        assertEquals(0, drugFee.compareTo(b1.getTotalAmount()), "两笔造数金额应同价（同一测试药）");

        // ---- preview（甲视角）----
        var authA = actAs("cashierV41a", "CASHIER");
        var pv = finance.shiftClosePreview(TODAY, authA).getData();
        BigDecimal paid = num(pv.get("sysPaid"));
        BigDecimal refund = num(pv.get("sysRefund"));
        BigDecimal net = num(pv.get("sysNet"));
        assertEquals(0, drugFee.multiply(new BigDecimal("2")).compareTo(paid), "甲当日恰两笔收款");
        assertEquals(0, drugFee.compareTo(refund), "甲当日恰一笔退费");
        assertEquals(0, paid.subtract(refund).compareTo(net), "净额必须 = 收款 - 退费");
        assertNull(pv.get("existing"), "尚未提交时不应有班结单");

        // ---- 口径一致性：preview 必须逐项等于 reconciliation 里甲那一行 ----
        actAs("reconAdminV41", "ADMIN");
        var rows = (List<Map<String, Object>>) finance.reconciliation(TODAY).getData().get("byCashier");
        var rowA = rows.stream().filter(r -> "班结收费员甲V41".equals(r.get("cashier")))
                .findFirst().orElseThrow(() -> new AssertionError("reconciliation 必须出现收费员甲"));
        assertEquals(0, num(rowA.get("paid_amount")).compareTo(paid), "收款口径与交款核查必须一致");
        assertEquals(0, num(rowA.get("refund_amount")).compareTo(refund), "退费口径与交款核查必须一致");
        // 乙的账不得混进甲的 preview（reconciliation 里乙自成一行）
        assertTrue(rows.stream().anyMatch(r -> "班结收费员乙V41".equals(r.get("cashier"))), "乙应自成一行");

        // ---- 提交班结：实点比系统净额多 3.50（长款）----
        actAs("cashierV41a", "CASHIER");
        BigDecimal declared = net.add(new BigDecimal("3.50"));
        var submitted = finance.shiftCloseSubmit(
                Map.<String, Object>of("date", TODAY, "declaredCash", declared.toPlainString(),
                        "note", "长款3.5待查"), authA);
        assertEquals(0, submitted.getCode(), submitted.getMessage());
        Long shiftId = ((Number) submitted.getData().get("id")).longValue();
        assertEquals(0, new BigDecimal("3.50").compareTo(num(submitted.getData().get("diff"))), "差额 = 实点 - 系统净额");
        assertEquals("SUBMITTED", submitted.getData().get("status"));
        // 快照落库，且与 preview 一致
        var stored = jdbc.queryForMap("select * from fin_cashier_shift where id = ?", shiftId);
        assertEquals(0, paid.compareTo(num(stored.get("sys_paid"))));
        assertEquals(0, refund.compareTo(num(stored.get("sys_refund"))));
        assertEquals(0, net.compareTo(num(stored.get("sys_net"))));

        // ---- 重复提交（同一收费员同一天）----
        assertEquals(5020, finance.shiftCloseSubmit(
                Map.<String, Object>of("date", TODAY, "declaredCash", "1.00"), authA).getCode(),
                "同日重复提交须被拒");
        // 实点金额非法
        assertEquals(5021, finance.shiftCloseSubmit(
                Map.<String, Object>of("date", TODAY, "declaredCash", "-1"), authA).getCode());

        // ---- preview 再次调用应带出已提交的班结单 ----
        var pv2 = finance.shiftClosePreview(TODAY, authA).getData();
        assertNotNull(pv2.get("existing"), "已提交后 preview 须带出班结单供前端禁用提交");
        assertEquals("SUBMITTED", ((Map<String, Object>) pv2.get("existing")).get("status"));

        // ---- 财务确认 ----
        var authFin = actAs("finadminV41", "ADMIN");
        assertEquals(0, finance.shiftCloseConfirm(shiftId, authFin).getCode());
        var after = jdbc.queryForMap("select status, confirmed_by from fin_cashier_shift where id = ?", shiftId);
        assertEquals("CONFIRMED", after.get("status"));
        assertEquals(uidFin.longValue(), ((Number) after.get("confirmed_by")).longValue(), "确认人须留痕");

        // 重复确认（模拟两位财务并发点击）：条件更新拦住，不再改写确认痕迹
        assertEquals(5023, finance.shiftCloseConfirm(shiftId, authFin).getCode());
        assertEquals(5022, finance.shiftCloseConfirm(-999L, authFin).getCode(), "不存在的班结单");
    }

    /** 非 ADMIN 不得确认；收费员列表只见自己，ADMIN 看全院 */
    @Test
    void confirmIsAdminOnlyAndListIsScopedByRole() {
        Long uidA = newUser("cashierV41c", "班结收费员丙V41");
        Long uidB = newUser("cashierV41d", "班结收费员丁V41");
        settleBy(uidA, "班结丙一V41");
        settleBy(uidB, "班结丁一V41");

        var authC = actAs("cashierV41c", "CASHIER");
        var subC = finance.shiftCloseSubmit(
                Map.<String, Object>of("date", TODAY, "declaredCash", "0.00"), authC);
        assertEquals(0, subC.getCode(), subC.getMessage());
        Long shiftC = ((Number) subC.getData().get("id")).longValue();

        var authD = actAs("cashierV41d", "CASHIER");
        assertEquals(0, finance.shiftCloseSubmit(
                Map.<String, Object>of("date", TODAY, "declaredCash", "0.00"), authD).getCode());

        // 收费员丁尝试确认丙的班结单 → 方法级 @PreAuthorize 拦在业务码之前
        assertThrows(AccessDeniedException.class, () -> finance.shiftCloseConfirm(shiftC, authD),
                "班结确认是财务职责，收费员不得自行确认");

        // 列表越权：丁只能看到自己那张，看不到丙的（服务端按登录态钉死过滤，不看请求参数）
        var listD = finance.shiftCloseList(TODAY, null, authD).getData();
        assertFalse(listD.isEmpty());
        assertTrue(listD.stream().allMatch(r -> uidB.equals(((Number) r.get("cashier_id")).longValue())),
                "收费员列表只能出现自己的班结单");
        assertTrue(listD.stream().noneMatch(r -> ((Number) r.get("id")).longValue() == shiftC),
                "丙的班结单不得出现在丁的列表里");

        // ADMIN 看全院：丙丁两张都在
        var authAdmin = actAs("finadminV41b", "ADMIN");
        var listAdmin = finance.shiftCloseList(TODAY, "SUBMITTED", authAdmin).getData();
        assertTrue(listAdmin.stream().anyMatch(r -> uidA.equals(((Number) r.get("cashier_id")).longValue())));
        assertTrue(listAdmin.stream().anyMatch(r -> uidB.equals(((Number) r.get("cashier_id")).longValue())));
        assertTrue(listAdmin.stream().allMatch(r -> "SUBMITTED".equals(r.get("status"))), "status 过滤应生效");
    }
}
