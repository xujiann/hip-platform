package cn.hip.outpatient.service;

import cn.hip.insurance.service.InsuranceSplitService;
import cn.hip.outpatient.entity.OutpCharge;
import cn.hip.outpatient.entity.OutpOrder;
import cn.hip.outpatient.repository.OutpChargeRepository;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.repository.OutpRegistrationRepository;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.integration.insurance.InsuranceAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChargeService {

    private final OutpOrderRepository orderRepository;
    private final OutpChargeRepository chargeRepository;
    private final OutpRegistrationRepository registrationRepository;
    private final InsuranceAdapter insuranceAdapter;
    private final InsuranceSplitService insuranceSplitService;
    private final cn.hip.platform.core.service.ConfigReader configReader;

    /** 结算：全部未收费订单一次结清 */
    @Transactional
    public OutpCharge settle(Long registrationId, String payMethod, Long cashierId) {
        List<OutpOrder> unpaid = orderRepository.findByRegistrationIdAndStatusOrderByIdAsc(registrationId, "CREATED");
        if (unpaid.isEmpty()) {
            throw new BizException(5001, "没有待收费项目");
        }
        BigDecimal total = unpaid.stream().map(OutpOrder::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        OutpCharge charge = new OutpCharge();
        charge.setRegistrationId(registrationId);
        charge.setTotalAmount(total);
        charge.setPayMethod(payMethod == null ? "CASH" : payMethod);
        charge.setCashierId(cashierId);
        charge.setChargeNo("TEMP");
        charge = chargeRepository.save(charge);
        charge.setChargeNo("%s%s-%06d".formatted(
                configReader.get("billno_prefix_charge", "SJ"),
                LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE), charge.getId()));
        charge = chargeRepository.save(charge);

        // 条件更新 + 判定行数：读-判-写会让窗口结算与患者端扫码各出一张 PAID 单（双倍扣款）
        var unpaidIds = unpaid.stream().map(OutpOrder::getId).toList();
        if (orderRepository.claimCharge(unpaidIds, charge.getId()) != unpaidIds.size()) {
            throw new BizException(5008, "费用明细已变化（可能已被结算或作废），请刷新后重试");
        }
        if ("YB".equals(charge.getPayMethod())) {
            Long patientId = registrationRepository.findById(registrationId).orElseThrow().getPatientId();
            insuranceSplitService.splitAndAudit("OUTP", charge.getChargeNo(), patientId, total,
                    unpaid.stream().map(o -> new InsuranceSplitService.YbLine(
                            o.getOrderType(), o.getItemCode(), o.getItemName(), o.getAmount(), o.getQty())).toList());
            var res = insuranceAdapter.uploadSettlement(charge.getChargeNo(), total);
            if (!res.ok()) {
                throw new BizException(5006, "医保结算上传失败: " + res.message());
            }
            charge.setYbSettleNo(res.settleNo());
            charge = chargeRepository.save(charge);
        }
        return charge;
    }

    public List<OutpCharge> recentCharges() {
        return chargeRepository.findTop50ByOrderByIdDesc();
    }

    /** 退费：单内订单均未发药/未执行才可退；订单退回已开立（退号单的挂号费直接作废） */
    @Transactional
    public OutpCharge refund(Long chargeId, Long operatorId) {
        OutpCharge charge = chargeRepository.findById(chargeId)
                .orElseThrow(() -> new BizException(5002, "结算单不存在"));
        if (!"PAID".equals(charge.getStatus())) {
            throw new BizException(5003, "该结算单已退费");
        }
        List<OutpOrder> orders = orderRepository.findByChargeId(chargeId);
        for (OutpOrder o : orders) {
            if ("DISPENSED".equals(o.getStatus())) {
                throw new BizException(5004, "已发药项目需先退药: " + o.getItemName());
            }
            if ("EXECUTED".equals(o.getStatus())) {
                throw new BizException(5005, "已执行项目不可退费: " + o.getItemName());
            }
        }
        // 条件更新：并发发药会把行改成 DISPENSED，此时行数不足即整单回滚（防"钱已退、药也发了"）
        var ids = orders.stream().map(OutpOrder::getId).toList();
        if (orderRepository.claimRefund(ids) != ids.size()) {
            throw new BizException(5009, "项目状态已变化（可能正在发药或执行），退费终止");
        }
        charge.setStatus("REFUNDED");
        charge.setRefundedAt(java.time.Instant.now());   // 日结按退费日归集，不再改写历史日报表
        charge.setRefundBy(operatorId);                  // 甲收乙退时账各归各，交款核查才有意义
        OutpCharge saved = chargeRepository.save(charge);
        if ("YB".equals(charge.getPayMethod())) {
            // 本地写全部完成后才碰渠道（1.1.6 B-3）：渠道失败→本地随事务回滚，报文未产生副作用前
            // 单据不动；渠道成功后事务内无任何可失败步骤，杜绝「医保已冲、本地未退」
            insuranceSplitService.reverse(charge.getChargeNo());
            var res = insuranceAdapter.uploadRefund(charge.getChargeNo());
            if (!res.ok()) {
                throw new BizException(5007, "医保退费冲正失败: " + res.message());
            }
        }
        return saved;
    }
}
