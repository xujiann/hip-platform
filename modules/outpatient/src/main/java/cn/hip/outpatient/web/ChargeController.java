package cn.hip.outpatient.web;

import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.repository.OutpRegistrationRepository;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import cn.hip.platform.empi.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/outpatient/charges")
@RequiredArgsConstructor
public class ChargeController {

    private final ChargeService chargeService;
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
    public R<Object> refund(@PathVariable Long chargeId) {
        try {
            return R.ok(chargeService.refund(chargeId));
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
