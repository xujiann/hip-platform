package cn.hip.outpatient.web;

import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.repository.OutpRegistrationRepository;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.RefundApprovalService;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import cn.hip.platform.empi.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/outpatient/charges")
@PreAuthorize("hasAnyRole('ADMIN','CASHIER')")   // 1.0.6：收费与退费限收费员
@RequiredArgsConstructor
public class ChargeController {

    private final ChargeService chargeService;
    private final RefundApprovalService refundApprovalService;
    private final OutpOrderRepository orderRepository;
    private final OutpRegistrationRepository registrationRepository;
    private final PatientRepository patientRepository;
    private final CurrentUserService currentUserService;

    /** 收费队列：有未收费订单的挂号 */
    @GetMapping("/worklist")
    public R<List<Map<String, Object>>> worklist() {
        return R.ok(orderRepository.registrationIdsWithUnchargedOrders().stream()
                .map(id -> (Map<String, Object>) regSummary(id)).toList());
    }

    /** 某挂号的未收费明细 */
    @GetMapping("/unpaid")
    public R<Map<String, Object>> unpaid(@RequestParam Long registrationId) {
        var orders = orderRepository.findByRegistrationIdAndStatusOrderByIdAsc(registrationId, "CREATED");
        var m = regSummary(registrationId);
        m.put("orders", orders);
        m.put("total", orders.stream().map(o -> o.getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add));
        return R.ok(m);
    }

    /** 最近结算单（退费入口） */
    @GetMapping("/recent")
    public R<List<Map<String, Object>>> recent() {
        return R.ok(chargeService.recentCharges().stream().map(c -> {
            var m = regSummary(c.getRegistrationId());
            m.put("chargeId", c.getId());
            m.put("chargeNo", c.getChargeNo());
            m.put("totalAmount", c.getTotalAmount());
            m.put("payMethod", c.getPayMethod());
            m.put("status", c.getStatus());
            m.put("createdAt", c.getCreatedAt());
            return (Map<String, Object>) m;
        }).toList());
    }

    @PostMapping("/{chargeId}/refund")
    public R<Object> refund(@PathVariable Long chargeId, Authentication auth) {
        try {
            return R.ok(chargeService.refund(chargeId, currentUserService.idOf(auth)));
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    // ---- 大额退费审批链（v30）：超阈值退费须先申请、授权人通过后才能执行 ----

    public record RefundApplyRequest(String reason) {}

    /** 收费员提交退费审批申请 */
    @PostMapping("/{chargeId}/refund-approval")
    public R<Object> applyRefundApproval(@PathVariable Long chargeId, @RequestBody(required = false) RefundApplyRequest req,
                                         Authentication auth) {
        try {
            Long id = refundApprovalService.apply(chargeId, req == null ? null : req.reason(),
                    currentUserService.idOf(auth));
            return R.ok(java.util.Map.of("approvalId", id));
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 待审批退费列表（授权人视角） */
    @GetMapping("/refund-approvals/pending")
    @PreAuthorize("hasRole('ADMIN')")   // 审批权限限管理员/授权人，不能自己申请自己批
    public R<Object> pendingRefundApprovals() {
        return R.ok(refundApprovalService.pendingList());
    }

    public record RefundDecideRequest(boolean approved, String note) {}

    /** 授权人审批：通过/驳回 */
    @PostMapping("/refund-approvals/{approvalId}/decide")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Object> decideRefundApproval(@PathVariable Long approvalId, @RequestBody RefundDecideRequest req,
                                          Authentication auth) {
        try {
            refundApprovalService.decide(approvalId, req.approved(), req.note(), currentUserService.idOf(auth));
            return R.ok(null);
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    public record SettleRequest(Long registrationId, String payMethod) {}

    @PostMapping("/settle")
    public R<Object> settle(@RequestBody SettleRequest req, Authentication auth) {
        try {
            return R.ok(chargeService.settle(req.registrationId(), req.payMethod(), currentUserService.idOf(auth)));
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    private LinkedHashMap<String, Object> regSummary(Long registrationId) {
        var m = new LinkedHashMap<String, Object>();
        m.put("registrationId", registrationId);
        registrationRepository.findById(registrationId).ifPresent(r -> {
            m.put("regNo", r.getRegNo());
            m.put("visitDate", r.getVisitDate());
            patientRepository.findById(r.getPatientId()).ifPresent(p -> {
                m.put("patientNo", p.getPatientNo());
                m.put("patientName", p.getName());
            });
        });
        return m;
    }
}
