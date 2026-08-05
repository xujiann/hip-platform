package cn.hip.outpatient.service;

import cn.hip.outpatient.entity.OutpOrder;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.integration.pay.PayAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 二十九期：扫码支付——出码 → （Mock 回调）确认 → 自动结算，全流程留痕 */
@Service
@RequiredArgsConstructor
public class PayService {

    private final JdbcTemplate jdbc;
    private final OutpOrderRepository orderRepository;
    private final ChargeService chargeService;
    private final PayAdapter payAdapter;

    private static final Set<String> CHANNELS = Set.of("WECHAT", "ALIPAY");

    /** 出码：按挂号待收费总额生成支付单 */
    @Transactional
    public Map<String, Object> createPayOrder(Long registrationId, String channel) {
        if (!CHANNELS.contains(channel)) throw new BizException(5101, "支付渠道只能为 WECHAT/ALIPAY");
        List<OutpOrder> unpaid = orderRepository.findByRegistrationIdAndStatusOrderByIdAsc(registrationId, "CREATED");
        if (unpaid.isEmpty()) throw new BizException(5001, "没有待收费项目");
        Integer pending = jdbc.queryForObject("""
                select count(*) from pay_order where registration_id = ? and status = 'PENDING'
                """, Integer.class, registrationId);
        if (pending != null && pending > 0) throw new BizException(5102, "已有待支付二维码，请先支付或作废");
        BigDecimal total = unpaid.stream().map(OutpOrder::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        String payNo = "ZF" + System.currentTimeMillis() % 1000000000L;
        var res = payAdapter.createPayment(payNo, total, channel);
        if (!res.ok()) throw new BizException(5103, "支付下单失败: " + res.message());
        jdbc.update("""
                insert into pay_order(pay_no, registration_id, amount, channel, qr_content) values (?,?,?,?,?)
                """, payNo, registrationId, total, channel, res.qrContent());
        return Map.of("payNo", payNo, "amount", total, "qrContent", res.qrContent());
    }

    /** 支付确认（Mock 回调 / 患者端确认）：置成功并自动结算 */
    @Transactional
    public Map<String, Object> confirm(String payNo, Long cashierId) {
        var rows = jdbc.queryForList("select * from pay_order where pay_no = ?", payNo);
        if (rows.isEmpty()) throw new BizException(5104, "支付单不存在");
        var po = rows.get(0);
        if (!"PENDING".equals(po.get("status"))) throw new BizException(5105, "支付单已处理");
        Long registrationId = ((Number) po.get("registration_id")).longValue();
        var charge = chargeService.settle(registrationId, (String) po.get("channel"), cashierId);
        jdbc.update("update pay_order set status = 'SUCCESS', paid_at = now(), charge_id = ? where pay_no = ?",
                charge.getId(), payNo);
        return Map.of("chargeNo", charge.getChargeNo(), "amount", charge.getTotalAmount());
    }

    /** 作废未支付二维码 */
    @Transactional
    public void cancel(String payNo) {
        int n = jdbc.update("update pay_order set status = 'CANCELLED' where pay_no = ? and status = 'PENDING'", payNo);
        if (n == 0) throw new BizException(5105, "支付单不存在或已处理");
    }
}
