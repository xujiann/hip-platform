package cn.hip.outpatient.web;

import cn.hip.outpatient.entity.OutpCriticalAlert;
import cn.hip.outpatient.repository.OutpCriticalAlertRepository;
import cn.hip.outpatient.repository.OutpLabResultRepository;
import cn.hip.outpatient.repository.OutpRegistrationRepository;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import cn.hip.platform.empi.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 检验结果查询与危急值处理 */
@RestController
@RequestMapping("/api/outpatient")
@RequiredArgsConstructor
public class LabResultController {

    private final OutpLabResultRepository resultRepository;
    private final OutpCriticalAlertRepository alertRepository;
    private final OutpRegistrationRepository registrationRepository;
    private final PatientRepository patientRepository;
    private final CurrentUserService currentUserService;

    @GetMapping("/lab-results")
    public R<Object> results(@RequestParam Long orderId) {
        return R.ok(resultRepository.findByOrderIdOrderByIdAsc(orderId));
    }

    @GetMapping("/critical-alerts")
    public R<List<Map<String, Object>>> alerts(@RequestParam(defaultValue = "NEW") String status) {
        var list = "ALL".equals(status)
                ? alertRepository.findTop50ByOrderByIdDesc()
                : alertRepository.findByStatusOrderByIdDesc(status);
        return R.ok(list.stream().map(a -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", a.getId());
            m.put("content", a.getContent());
            m.put("status", a.getStatus());
            m.put("createdAt", a.getCreatedAt());
            registrationRepository.findById(a.getRegistrationId()).ifPresent(r ->
                    patientRepository.findById(r.getPatientId()).ifPresent(p -> {
                        m.put("patientNo", p.getPatientNo());
                        m.put("patientName", p.getName());
                    }));
            return (Map<String, Object>) m;
        }).toList());
    }

    @PutMapping("/critical-alerts/{id}/handle")
    public R<Void> handle(@PathVariable Long id, Authentication auth) {
        OutpCriticalAlert a = alertRepository.findById(id).orElse(null);
        if (a == null) return R.fail(7101, "告警不存在");
        a.setStatus("HANDLED");
        a.setHandlerId(currentUserService.idOf(auth));
        a.setHandledAt(Instant.now());
        alertRepository.save(a);
        return R.ok();
    }
}
