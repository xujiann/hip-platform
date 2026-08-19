package cn.hip.server;

import cn.hip.cdr.service.CdrSyncService;
import cn.hip.insurance.service.InsuranceReconService;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 1.2.3 五轮审阅回归：CSV 负数口径、住院 orphanRefund、CDR 水位取抽取开始时刻、门户粗锁 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class Phase123ReviewTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired PatientService patientService;
    @Autowired ChargeService chargeService;
    @Autowired InsuranceReconService reconService;
    @Autowired CdrSyncService cdrSyncService;
    @Autowired cn.hip.outpatient.service.RegistrationService registrationService;
    @Autowired cn.hip.outpatient.service.DoctorStationService doctorStationService;
    @Autowired cn.hip.outpatient.repository.OutpScheduleRepository scheduleRepository;
    @Autowired cn.hip.server.support.TestSeeds seeds;

    private Long regWithOrder(String name) {
        Patient p = new Patient();
        p.setName(name);
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        var sch = new cn.hip.outpatient.entity.OutpSchedule();
        sch.setDeptId(1L);
        sch.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
        sch.setFee(BigDecimal.ZERO);
        sch.setCapacity(9);
        sch = scheduleRepository.save(sch);
        Long regId = registrationService.register(pid, sch.getId()).getId();
        doctorStationService.startVisit(regId, null);
        doctorStationService.createOrders(regId, List.of(new cn.hip.outpatient.service
                .DoctorStationService.OrderLine("DRUG", seeds.drug("通用测试药").getId(),
                1, "口服", "qd", "1粒", null)), null);
        entityManager.flush();
        return regId;
    }

    /** P1-3：退款行负数金额不得被公式守卫加 ' 前缀——否则 Excel SUM 跳过，净额口径被击穿 */
    @Test
    void csvNegativeAmountStaysNumeric() throws Exception {
        Long regId = regWithOrder("CSV负数123");
        var charge = chargeService.settle(regId, "CASH", null);
        chargeService.refund(charge.getId(), null);
        entityManager.flush();

        String d = cn.hip.platform.core.config.BusinessDates.today().toString();
        var res = mockMvc.perform(get("/api/reports/daily-settlement.csv?date=" + d))
                .andExpect(status().isOk()).andReturn();
        String csv = res.getResponse().getContentAsString();
        String refundLine = csv.lines()
                .filter(l -> l.startsWith("退款,") && l.contains(charge.getChargeNo()))
                .findFirst().orElseThrow(() -> new AssertionError("CSV 应含该单的退款行"));
        assertFalse(refundLine.contains(",'-"), "负数金额被加 ' 前缀即变文本：" + refundLine);
        assertTrue(refundLine.contains(",-"), "退款行金额应为负数：" + refundLine);
    }

    /** P1-1：住院 PAID 单存在冲正报文必须标异常（渠道已冲、本地未冲销的残留形态） */
    @Test
    void inpPaidWithRefundMessageIsFlagged() {
        Patient p = new Patient();
        p.setName("住院悬账123");
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        entityManager.flush();
        String settleNo = "CY99991231-S123999";
        jdbc.update("""
                insert into inp_admission(admission_no, patient_id, dept_id, ward_id, bed_id, status)
                values ('ZY-T123', ?, 1, 1, (select id from inp_bed limit 1), 'DISCHARGED')
                """, pid);
        Long admId = jdbc.queryForObject("select id from inp_admission where admission_no = 'ZY-T123'", Long.class);
        jdbc.update("""
                insert into inp_settlement(settle_no, admission_id, total_amount, deposit_amount, balance,
                                           pay_method, status)
                values (?, ?, 100, 0, -100, 'YB', 'PAID')
                """, settleNo, admId);
        // 渠道存在冲正报文，但本地仍 PAID——cancelSettlement 渠道成功后提交失败会留下这个形态
        jdbc.update("""
                insert into int_message_log(direction, channel, ref_no, payload, status)
                values ('OUT', 'YB', ?, '{"api":"outpatient.refund","amount":100}', 'OK')
                """, settleNo);
        jdbc.update("""
                insert into int_message_log(direction, channel, ref_no, payload, status)
                values ('OUT', 'YB', ?, '{"api":"inpatient.settle","amount":100}', 'OK')
                """, settleNo);

        String today = cn.hip.platform.core.config.BusinessDates.today().toString();
        var row = reconService.reconRows(today).stream()
                .filter(r -> settleNo.equals(r.get("charge_no"))).findFirst().orElseThrow();
        assertFalse((Boolean) row.get("consistent"), "PAID 单存在冲正报文必须判不一致（此前住院侧判一致）");
        assertTrue(String.valueOf(row.get("note")).contains("冲正报文"));
    }

    /** P1-2：增量水位=抽取开始时刻，而非表 max(updated_at)——同步期间的变更不得被水位盖过 */
    @Test
    void cdrWatermarkTakesSyncStartNotTableMax() {
        Long regId = regWithOrder("水位123");
        // 模拟"同步期间/之后被更新的行"：把该挂号 updated_at 推到未来 1 小时。
        // 须临时停用自 touch 触发器（hip 是表 owner，可 alter）：它会把手工时间戳改写回 now()。
        // replica 模式需要超级用户，应用账号无权
        jdbc.execute("alter table outp_registration disable trigger trg_outp_registration_touch");
        jdbc.update("update outp_registration set updated_at = now() + interval '1 hour' where id = ?", regId);
        jdbc.execute("alter table outp_registration enable trigger trg_outp_registration_touch");
        jdbc.update("""
                insert into sys_config(cfg_key, cfg_value, remark) values ('cdr_sync_watermark', ?, 't')
                on conflict (cfg_key) do update set cfg_value = excluded.cfg_value
                """, Instant.now().minusSeconds(3600).toString());

        cdrSyncService.syncIncremental();
        String wm = jdbc.queryForObject(
                "select cfg_value from sys_config where cfg_key = 'cdr_sync_watermark'", String.class);
        Instant watermark = Instant.parse(wm);
        Instant futureRow = jdbc.queryForObject(
                "select updated_at from outp_registration where id = ?", java.sql.Timestamp.class, regId).toInstant();
        // 旧实现取三表 max → 水位被推到未来行之后，该行的后续变更永不重抽
        assertTrue(watermark.isBefore(futureRow),
                "水位必须是抽取开始时刻（%s），不得越过同步期间更新的行（%s）".formatted(watermark, futureRow));
    }

    /** P1-4：粗锁——同患者号跨 IP 失败达 50 次后，换新 IP 也被 9503 拦（XFF 伪造绕细锁的兜底） */
    @Test
    void portalCoarseLockAcrossIps() throws Exception {
        Patient p = new Patient();
        p.setName("粗锁123");
        p.setSex("M");
        p.setPhone("13800009999");
        String patientNo = patientService.register(p).getPatientNo();
        entityManager.flush();

        for (int i = 0; i < 50; i++) {
            mockMvc.perform(post("/api/portal/login").contentType("application/json")
                            .header("X-Forwarded-For", "10.9." + (i / 250) + "." + (i % 250 + 1))
                            .content("{\"patientNo\":\"%s\",\"phone\":\"wrong\"}".formatted(patientNo)))
                    .andExpect(jsonPath("$.code").value(9501));
        }
        // 第 51 个全新 IP：细锁键全新，但粗锁已达阈值
        mockMvc.perform(post("/api/portal/login").contentType("application/json")
                        .header("X-Forwarded-For", "10.99.99.99")
                        .content("{\"patientNo\":\"%s\",\"phone\":\"wrong\"}".formatted(patientNo)))
                .andExpect(jsonPath("$.code").value(9503));
        // 正确凭据在锁定期内同样被拒（锁定语义完整）
        mockMvc.perform(post("/api/portal/login").contentType("application/json")
                        .header("X-Forwarded-For", "10.99.99.98")
                        .content("{\"patientNo\":\"%s\",\"phone\":\"13800009999\"}".formatted(patientNo)))
                .andExpect(jsonPath("$.code").value(9503));
    }
}
