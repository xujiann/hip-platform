package cn.hip.outpatient.web;

import cn.hip.outpatient.entity.OutpRegistration;
import cn.hip.outpatient.repository.OutpRegistrationRepository;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.repository.SysDeptRepository;
import cn.hip.platform.core.repository.SysUserRepository;
import cn.hip.platform.empi.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/outpatient/registrations")
@RequiredArgsConstructor
public class OutpRegistrationController {

    private final RegistrationService registrationService;
    private final OutpRegistrationRepository registrationRepository;
    private final PatientRepository patientRepository;
    private final SysDeptRepository deptRepository;
    private final SysUserRepository userRepository;

    public record RegisterRequest(Long patientId, Long scheduleId) {}

    @PostMapping
    public R<Map<String, Object>> register(@RequestBody RegisterRequest req) {
        try {
            return R.ok(toDto(registrationService.register(req.patientId(), req.scheduleId())));
        } catch (RegistrationService.BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @PutMapping("/{id}/cancel")
    public R<Map<String, Object>> cancel(@PathVariable Long id) {
        try {
            return R.ok(toDto(registrationService.cancel(id)));
        } catch (RegistrationService.BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @GetMapping
    public R<List<Map<String, Object>>> listByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return R.ok(registrationRepository.findByVisitDateOrderByIdDesc(date)
                .stream().map(this::toDto).toList());
    }

    private Map<String, Object> toDto(OutpRegistration r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", r.getId());
        m.put("regNo", r.getRegNo());
        m.put("patientId", r.getPatientId());
        patientRepository.findById(r.getPatientId()).ifPresent(p -> {
            m.put("patientNo", p.getPatientNo());
            m.put("patientName", p.getName());
            m.put("patientSex", p.getSex());
        });
        m.put("deptId", r.getDeptId());
        m.put("deptName", deptRepository.findById(r.getDeptId()).map(d -> d.getName()).orElse(""));
        m.put("doctorName", r.getDoctorId() == null ? ""
                : userRepository.findById(r.getDoctorId()).map(u -> u.getRealName()).orElse(""));
        m.put("visitDate", r.getVisitDate());
        m.put("fee", r.getFee());
        m.put("status", r.getStatus());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }
}
