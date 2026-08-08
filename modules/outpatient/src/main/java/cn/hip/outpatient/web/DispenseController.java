package cn.hip.outpatient.web;

import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.repository.OutpRegistrationRepository;
import cn.hip.outpatient.service.DispenseService;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.core.common.R;
import cn.hip.platform.empi.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/outpatient/dispense")
@PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")   // 1.0.6：发药与退药限药师
@RequiredArgsConstructor
public class DispenseController {

    private final DispenseService dispenseService;
    private final OutpOrderRepository orderRepository;
    private final OutpRegistrationRepository registrationRepository;
    private final PatientRepository patientRepository;

    /** 发药队列：有已收费待发药处方的挂号 */
    @GetMapping("/worklist")
    public R<List<Map<String, Object>>> worklist() {
        return R.ok(orderRepository.registrationIdsWithChargedDrugs().stream().map(regId -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("registrationId", regId);
            registrationRepository.findById(regId).ifPresent(r ->
                    patientRepository.findById(r.getPatientId()).ifPresent(p -> {
                        m.put("patientNo", p.getPatientNo());
                        m.put("patientName", p.getName());
                    }));
            m.put("orders", orderRepository
                    .findByRegistrationIdAndOrderTypeAndStatusOrderByIdAsc(regId, "DRUG", "CHARGED"));
            return (Map<String, Object>) m;
        }).toList());
    }

    @PostMapping("/{registrationId}")
    public R<Object> dispense(@PathVariable Long registrationId) {
        try {
            return R.ok(dispenseService.dispense(registrationId));
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 已发药记录（退药入口）：按挂号查询 */
    @GetMapping("/dispensed")
    public R<Object> dispensed(@RequestParam Long registrationId) {
        return R.ok(orderRepository
                .findByRegistrationIdAndOrderTypeAndStatusOrderByIdAsc(registrationId, "DRUG", "DISPENSED"));
    }

    @PostMapping("/orders/{orderId}/return")
    public R<Object> returnDrug(@PathVariable Long orderId,
                                org.springframework.security.core.Authentication auth) {
        try {
            return R.ok(dispenseService.returnDrug(orderId, null));
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }
}
