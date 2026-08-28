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
import cn.hip.platform.core.config.BusinessDates;

@Service
@RequiredArgsConstructor
public class ChargeService {

    private final OutpOrderRepository orderRepository;
    private final OutpChargeRepository chargeRepository;
    private final OutpRegistrationRepository registrationRepository;
    private final InsuranceAdapter insuranceAdapter;
    private final InsuranceSplitService insuranceSplitService;
    private final cn.hip.platform.core.service.ConfigReader configReader;
    private final cn.hip.platform.core.config.ModuleGate moduleGate;
    private final RefundApprovalService refundApprovalService;

    /** 结算：全部未收费订单一次结清 */
    @Transactional
    public OutpCharge settle(Long registrationId, String payMethod, Long cashierId) {
        // 模块开关须挡横向调用（1.2.0）：关掉 insurance 只挡菜单与 /api/insurance 路由时，
        // 收费传 payMethod=YB 仍会完整走分割写 yb_* 表——未采购医保的医院对账时无从解释
        if ("YB".equals(payMethod) && !moduleGate.isEnabled("insurance")) {
            throw new BizException(5010, "医保模块未启用，不能以医保方式结算");
        }
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
                BusinessDates.today().format(DateTimeFormatter.BASIC_ISO_DATE), charge.getId()));
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
        String chargeNo = charge.getChargeNo();
        String payMethod = charge.getPayMethod();
        // 提示层（非防线）：单据已退时直接给准确话术。少了这层，二次退费会因明细已被摘走而
        // 落到 5009「可能正在发药」——对重复点击退费的收费员是误导。真防线是下方的条件更新
        if (!"PAID".equals(charge.getStatus())) {
            throw new BizException(5003, "该结算单已退费");
        }
        // 大额退费审批闸（v30）：超阈值须先有 APPROVED 审批单，否则拒绝并引导走审批。
        // 放在抢占逻辑之前——审批未过时不触碰任何单据/明细状态；消费审批单是幂等条件更新
        refundApprovalService.assertApproved(charge);
        List<OutpOrder> orders = orderRepository.findByChargeId(chargeId);
        // 空列表必须显式拒绝：明细抢占会把 chargeId 置 null，他方先退后本方查得空集，
        // 若放任则 `0 != 0` 为假、防线在最需要它时反而放行（settle/dispense 早有 isEmpty 拦截，此处漏了）
        if (orders.isEmpty()) {
            throw new BizException(5009, "项目状态已变化（可能正在发药或执行），退费终止");
        }
        // 只读校验一律前置于抢占：校验失败不该改单据状态。
        // 此处读到的状态可能过时（读后被发药），由下方明细抢占的行数判定兜底
        for (OutpOrder o : orders) {
            if ("DISPENSED".equals(o.getStatus())) {
                throw new BizException(5004, "已发药项目需先退药: " + o.getItemName());
            }
            if ("EXECUTED".equals(o.getStatus())) {
                throw new BizException(5005, "已执行项目不可退费: " + o.getItemName());
            }
        }
        // 抢占单据本身：读-判-写让并发退费各自放行一次（YB 单会重复冲正医保额度）。
        // 本地时序下先行者已提交、后来者读到 REFUNDED 而"看起来正常"，CI 上六线程同时读到 PAID
        // 便全部通过——1.2.9 由 CI 实证。住院 claimCancel 一直如此，门诊漏了这一层。
        // 退费时刻（日结按退费日归集）与退费人（甲收乙退账各归各）随抢占一次写入，不再另行 save
        if (chargeRepository.claimRefund(chargeId, java.time.Instant.now(), operatorId) == 0) {
            throw new BizException(5003, "该结算单已退费");
        }
        // 明细抢占：并发发药会把行改成 DISPENSED，此时行数不足即整单回滚（防"钱已退、药也发了"）
        var ids = orders.stream().map(OutpOrder::getId).toList();
        if (orderRepository.claimRefund(ids) != ids.size()) {
            throw new BizException(5009, "项目状态已变化（可能正在发药或执行），退费终止");
        }
        if ("YB".equals(payMethod)) {
            // 本地写全部完成后才碰渠道（1.1.6 B-3）：渠道失败→本地随事务回滚，报文未产生副作用前
            // 单据不动；渠道成功后事务内无任何可失败步骤，杜绝「医保已冲、本地未退」
            insuranceSplitService.reverse(chargeNo);
            var res = insuranceAdapter.uploadRefund(chargeNo);
            if (!res.ok()) {
                throw new BizException(5007, "医保退费冲正失败: " + res.message());
            }
        }
        // 条件更新 clearAutomatically 后原实体已脱管，重新读回终态（住院 cancelSettlement 同此写法）
        return chargeRepository.findById(chargeId).orElseThrow();
    }
}
