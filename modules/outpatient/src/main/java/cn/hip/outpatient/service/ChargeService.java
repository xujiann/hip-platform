package cn.hip.outpatient.service;

import cn.hip.outpatient.entity.OutpCharge;
import cn.hip.outpatient.entity.OutpOrder;
import cn.hip.outpatient.repository.OutpChargeRepository;
import cn.hip.outpatient.repository.OutpOrderRepository;
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
    private final InsuranceAdapter insuranceAdapter;
    private final InsuranceSettleService insuranceSettleService;

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
        charge.setChargeNo("SJ%s-%06d".formatted(
                LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE), charge.getId()));
        charge = chargeRepository.save(charge);

        for (OutpOrder o : unpaid) {
            o.setStatus("CHARGED");
            o.setChargeId(charge.getId());
            orderRepository.save(o);
        }
        if ("YB".equals(charge.getPayMethod())) {
            insuranceSettleService.splitAndAudit(charge, unpaid);
            var res = insuranceAdapter.uploadSettlement(charge.getChargeNo(), total);
            if (!res.ok()) {
                throw new BizException(5006, "医保结算上传失败: " + res.message());
            }
        }
        return charge;
    }

    public List<OutpCharge> recentCharges() {
        return chargeRepository.findTop50ByOrderByIdDesc();
    }

    /** 退费：单内订单均未发药/未执行才可退；订单退回已开立（退号单的挂号费直接作废） */
    @Transactional
    public OutpCharge refund(Long chargeId) {
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
        for (OutpOrder o : orders) {
            o.setStatus("CREATED");
            o.setChargeId(null);
            orderRepository.save(o);
        }
        if ("YB".equals(charge.getPayMethod())) {
            insuranceAdapter.uploadRefund(charge.getChargeNo());
        }
        charge.setStatus("REFUNDED");
        return chargeRepository.save(charge);
    }
}
