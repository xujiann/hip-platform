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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
        // 单号取序列：millis%1e9 同毫秒并发即撞唯一约束，且约 11.6 天回绕撞历史单
        Long seq = jdbc.queryForObject("select nextval('pay_order_seq')", Long.class);
        String payNo = "ZF" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + seq;
        var res = payAdapter.createPayment(payNo, total, channel);
        if (!res.ok()) throw new BizException(5103, "支付下单失败: " + res.message());
        try {
            jdbc.update("""
                    insert into pay_order(pay_no, registration_id, amount, channel, qr_content) values (?,?,?,?,?)
                    """, payNo, registrationId, total, channel, res.qrContent());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // uq_pay_order_pending：并发出码只允许一张待支付单（否则两笔钱只有一笔能结算）
            throw new BizException(5102, "已有待支付二维码，请先支付或作废");
        }
        return Map.of("payNo", payNo, "amount", total, "qrContent", res.qrContent());
    }

    /** 支付确认（Mock 回调 / 患者端确认）：置成功并自动结算 */
    @Transactional
    public Map<String, Object> confirm(String payNo, Long cashierId) {
        var rows = jdbc.queryForList("select * from pay_order where pay_no = ?", payNo);
        if (rows.isEmpty()) throw new BizException(5104, "支付单不存在");
        var po = rows.get(0);
        // 先抢占 PENDING→SUCCESS：读-判-写会让并发 confirm 各触发一次结算
        if (jdbc.update("update pay_order set status = 'SUCCESS', paid_at = now() "
                + "where pay_no = ? and status = 'PENDING'", payNo) == 0) {
            throw new BizException(5105, "支付单已处理");
        }
        Long registrationId = ((Number) po.get("registration_id")).longValue();
        var charge = chargeService.settle(registrationId, (String) po.get("channel"), cashierId);
        // 出码后医生若增删医嘱，结算额会与患者实付不符——不一致即回滚，要求重新出码
        BigDecimal paid = (BigDecimal) po.get("amount");
        if (paid != null && paid.compareTo(charge.getTotalAmount()) != 0) {
            throw new BizException(5106, "费用已变化（应付 " + charge.getTotalAmount()
                    + "，支付单 " + paid + "），请作废二维码后重新出码");
        }
        jdbc.update("update pay_order set charge_id = ? where pay_no = ?", charge.getId(), payNo);
        return Map.of("chargeNo", charge.getChargeNo(), "amount", charge.getTotalAmount());
    }

    /** 作废未支付二维码 */
    @Transactional
    public void cancel(String payNo) {
        int n = jdbc.update("update pay_order set status = 'CANCELLED' where pay_no = ? and status = 'PENDING'", payNo);
        if (n == 0) throw new BizException(5105, "支付单不存在或已处理");
    }
}
