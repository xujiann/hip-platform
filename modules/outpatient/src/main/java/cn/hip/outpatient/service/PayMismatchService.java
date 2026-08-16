package cn.hip.outpatient.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 支付差错处置：实付与应结不符时，把支付单落到**不可重复消费的终态**并开人工差错单。
 *
 * <p>关键在 REQUIRES_NEW：调用方所在事务后续若因任何原因回滚，这条挂起状态也必须留下——
 * 否则支付单会退回 PENDING，而渠道那边钱已经扣了，患者按提示"重新出码"就会付第二次。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayMismatchService {

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markMismatch(String payNo, BigDecimal paid, BigDecimal payable, Long registrationId) {
        jdbc.update("update pay_order set status = 'MISMATCH' where pay_no = ?", payNo);
        jdbc.update("""
                insert into ops_fault_ticket(title, level, reporter)
                values (?, 'HIGH', 'system')
                """, "[支付差错] 支付单 " + payNo + " 实付 " + paid + " 与应结 " + payable
                + " 不符（挂号 " + registrationId + "），需人工核对退款或补收");
        log.warn("支付差错挂起: payNo={} 实付={} 应结={} 挂号={}", payNo, paid, payable, registrationId);
    }
}
