package cn.hip.outpatient.web;

import cn.hip.outpatient.entity.OutpOrder;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.repository.OutpRegistrationRepository;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import cn.hip.platform.empi.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 药师审方：未审处方队列 → 通过/拒绝（拒绝即作废该行） */
@RestController
@RequestMapping("/api/outpatient/review")
@PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")   // 1.0.6：审方限药师
@RequiredArgsConstructor
public class ReviewController {

    private final OutpOrderRepository orderRepository;
    private final OutpRegistrationRepository registrationRepository;
    private final PatientRepository patientRepository;
    private final CurrentUserService currentUserService;
    private final cn.hip.platform.core.service.ConfigReader configReader;

    @GetMapping("/pending")
    public R<List<Map<String, Object>>> pending() {
        // 1.0.1（505）：单次待审列表上限（review_pending_limit，0=不限）——避免药师积压领取
        int limit = configReader.getInt("review_pending_limit", 0);
        var drugs = orderRepository.findPendingReviewDrugs();
        if (limit > 0 && drugs.size() > limit) {
            drugs = drugs.subList(0, limit);
        }
        return R.ok(drugs.stream().map(o -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("orderId", o.getId());
            m.put("groupNo", o.getGroupNo());
            m.put("itemName", o.getItemName());
            m.put("spec", o.getSpec());
            m.put("qty", o.getQty());
            m.put("usage", "%s %s %s×%s天".formatted(
                    o.getUsageRoute(), o.getDosePerTime(), o.getFrequency(), o.getDays()));
            registrationRepository.findById(o.getRegistrationId()).ifPresent(r ->
                    patientRepository.findById(r.getPatientId()).ifPresent(p -> {
                        m.put("patientName", p.getName());
                        m.put("allergyHistory", p.getAllergyHistory());
                    }));
            return (Map<String, Object>) m;
        }).toList());
    }

    @PutMapping("/{orderId}/approve")
    @Transactional
    public R<Void> approve(@PathVariable Long orderId, Authentication auth) {
        return review(orderId, auth, true, null);
    }

    @PutMapping("/{orderId}/reject")
    @Transactional
    public R<Void> reject(@PathVariable Long orderId, @RequestParam String reason, Authentication auth) {
        return review(orderId, auth, false, reason);
    }

    private R<Void> review(Long orderId, Authentication auth, boolean approve, String reason) {
        OutpOrder o = orderRepository.findById(orderId).orElse(null);
        if (o == null || !"DRUG".equals(o.getOrderType())) return R.fail(4101, "处方不存在");
        if (o.getReviewStatus() != null) return R.fail(4102, "该处方已审核");
        o.setReviewStatus(approve ? "APPROVED" : "REJECTED");
        o.setReviewNote(reason);
        o.setReviewerId(currentUserService.idOf(auth));
        if (!approve) {
            if (!"CREATED".equals(o.getStatus())) return R.fail(4103, "已收费处方不能拒绝，请走退费");
            o.setStatus("CANCELLED");
        }
        orderRepository.save(o);
        return R.ok();
    }
}
