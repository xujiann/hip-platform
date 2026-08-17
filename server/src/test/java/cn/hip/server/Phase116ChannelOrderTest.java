package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.inpatient.service.InpatientService.InpException;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.platform.integration.insurance.InsuranceAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.1.6 B-3 失败路径：渠道调用是事务内最后一步——渠道冲正失败时本地必须整体回滚，
 * 不留"本地已冲销、医保未冲正"或反向的悬账。用可编程假适配器让 uploadRefund 定向失败。
 */
@SpringBootTest
// 有意不加 @Transactional：本测试验证的就是"渠道失败→事务回滚"，测试事务会把
// 服务事务变成参与者，assertThrows 接住异常后回滚根本不会发生（真并发/提交语义
// 测试范式见 StockRestoreConcurrencyTest；1.1.6 审阅 P2-9 的教训现场复现）
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class Phase116ChannelOrderTest {

    /** 可编程适配器：默认全通，可开关让冲正失败（@Primary 盖过 Mock 适配器） */
    @TestConfiguration
    static class FailingAdapterConfig {
        static final AtomicBoolean REFUND_FAILS = new AtomicBoolean(false);

        @Bean
        @Primary
        InsuranceAdapter programmableAdapter(cn.hip.platform.integration.service.IntegrationLogService logService) {
            return new InsuranceAdapter() {
                @Override
                public InsuranceResult uploadSettlement(String chargeNo, BigDecimal amount) {
                    logService.log("OUT", "YB", chargeNo,
                            "{\"api\":\"outpatient.settle\",\"chargeNo\":\"" + chargeNo + "\",\"amount\":" + amount + "}",
                            true, null);
                    return new InsuranceResult(true, "YBTEST-" + chargeNo, "ok");
                }

                @Override
                public InsuranceResult uploadRefund(String chargeNo) {
                    if (REFUND_FAILS.get()) {
                        return new InsuranceResult(false, null, "渠道故障（测试注入）");
                    }
                    logService.log("OUT", "YB", chargeNo,
                            "{\"api\":\"outpatient.refund\",\"chargeNo\":\"" + chargeNo + "\"}", true, null);
                    return new InsuranceResult(true, null, "ok");
                }
            };
        }
    }

    @Autowired InpatientService inpatientService;
    @Autowired PatientService patientService;
    @Autowired JdbcTemplate jdbc;

    private Long admIdForCleanup;

    @AfterEach
    void reset() {
        FailingAdapterConfig.REFUND_FAILS.set(false);
        // 真提交模式须自净：召回成功的患者要再次出院释放床位（E2E 同纪律）
        if (admIdForCleanup != null) {
            try {
                inpatientService.discharge(admIdForCleanup, null, "CASH");
            } catch (Exception ignored) {
            }
        }
    }

    private Long admitYbAndDischarge(String name) {
        Patient p = new Patient();
        p.setName(name);
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        jdbc.update("update empi_patient set insurance_type = 'YB_STAFF' where id = ?", pid);
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        Long admId = inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎",
                new BigDecimal("500"), "CASH", null).getId();
        inpatientService.discharge(admId, null, "YB");
        return admId;
    }

    /** 冲正失败 → 9023 → 结算仍 PAID、患者仍出院、床位未被占——本地零残留，重试安全 */
    @Test
    void refundFailureRollsBackEverything() {
        Long admId = admitYbAndDischarge("渠道失败116");
        Long bedBefore = jdbc.queryForObject(
                "select count(*) from inp_bed where status = 'OCCUPIED'", Long.class);

        FailingAdapterConfig.REFUND_FAILS.set(true);
        var e = assertThrows(InpException.class, () -> inpatientService.cancelSettlement(admId, null, null));
        assertEquals(9023, e.code);

        assertEquals("PAID", jdbc.queryForObject(
                "select status from inp_settlement where admission_id = ? order by id desc limit 1",
                String.class, admId), "渠道失败必须回滚冲销");
        assertEquals("DISCHARGED", jdbc.queryForObject(
                "select status from inp_admission where id = ?", String.class, admId));
        assertEquals(bedBefore, jdbc.queryForObject(
                "select count(*) from inp_bed where status = 'OCCUPIED'", Long.class), "床位占用必须回滚");
        // 年度累计不得被回退（reverse 的写随事务一起回滚）
        Boolean reversed = jdbc.queryForObject("""
                select coalesce(bool_or(reversed), false) from yb_settle_split
                where charge_no = (select settle_no from inp_settlement
                                   where admission_id = ? order by id desc limit 1)
                """, Boolean.class, admId);
        assertEquals(Boolean.FALSE, reversed, "冲销标记必须随事务回滚");

        // 渠道恢复后重试成功——失败未留任何残留状态
        FailingAdapterConfig.REFUND_FAILS.set(false);
        var adm = inpatientService.cancelSettlement(admId, null, null);
        assertEquals("IN_HOSPITAL", adm.getStatus());
        admIdForCleanup = admId;
    }
}
