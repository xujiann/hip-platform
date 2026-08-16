package cn.hip.outpatient.web;

import cn.hip.outpatient.entity.OutpDiagnosis;
import cn.hip.outpatient.entity.OutpEmr;
import cn.hip.outpatient.repository.*;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import cn.hip.platform.empi.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/outpatient/doctor")
@PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP')")   // 1.0.9：权限清点补齐
@RequiredArgsConstructor
public class DoctorStationController {

    private final DoctorStationService doctorStationService;
    private final OutpRegistrationRepository registrationRepository;
    private final OutpEmrRepository emrRepository;
    private final OutpDiagnosisRepository diagnosisRepository;
    private final OutpOrderRepository orderRepository;
    private final PatientRepository patientRepository;
    private final CurrentUserService currentUserService;
    private final cn.hip.platform.integration.signature.SignatureAdapter signatureAdapter;

    /** 接诊队列：当日挂号（含已诊，医生可回看） */
    @GetMapping("/worklist")
    public R<List<Map<String, Object>>> worklist(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return R.ok(registrationRepository.findByVisitDateOrderByIdDesc(date).stream()
                .filter(r -> !"CANCELLED".equals(r.getStatus()))
                .map(r -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("registrationId", r.getId());
                    m.put("regNo", r.getRegNo());
                    m.put("status", r.getStatus());
                    m.put("deptId", r.getDeptId());
                    patientRepository.findById(r.getPatientId()).ifPresent(p -> {
                        m.put("patientId", p.getId());
                        m.put("patientNo", p.getPatientNo());
                        m.put("patientName", p.getName());
                        m.put("sex", p.getSex());
                        m.put("age", cn.hip.platform.empi.service.PatientService.ageOf(p.getBirthDate()));
                        m.put("allergyHistory", p.getAllergyHistory());
                    });
                    return (Map<String, Object>) m;
                }).toList());
    }

    @PostMapping("/{registrationId}/start")
    public R<Map<String, Object>> start(@PathVariable Long registrationId, Authentication auth) {
        try {
            var reg = doctorStationService.startVisit(registrationId, currentUserService.idOf(auth));
            return R.ok(Map.of("registrationId", reg.getId(), "status", reg.getStatus()));
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 病历+诊断+医嘱一次取全（工作区加载） */
    @GetMapping("/{registrationId}/workspace")
    public R<Map<String, Object>> workspace(@PathVariable Long registrationId) {
        var m = new LinkedHashMap<String, Object>();
        m.put("emr", emrRepository.findByRegistrationId(registrationId).orElse(null));
        m.put("diagnoses", diagnosisRepository.findByRegistrationIdOrderByPrimaryDiagDescIdAsc(registrationId));
        m.put("orders", orderRepository.findByRegistrationIdOrderByIdAsc(registrationId));
        return R.ok(m);
    }

    public record SaveEmrRequest(OutpEmr emr, List<OutpDiagnosis> diagnoses) {}

    @PutMapping("/{registrationId}/emr")
    public R<OutpEmr> saveEmr(@PathVariable Long registrationId, @RequestBody SaveEmrRequest req,
                              Authentication auth) {
        try {
            return R.ok(doctorStationService.saveEmr(registrationId, req.emr(),
                    req.diagnoses() == null ? List.of() : req.diagnoses(), currentUserService.idOf(auth)));
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @PostMapping("/{registrationId}/emr/sign")
    public R<Object> signEmr(@PathVariable Long registrationId, Authentication auth) {
        try {
            var emr = doctorStationService.signEmr(registrationId, currentUserService.idOf(auth), signatureAdapter);
            return R.ok(Map.of("signature", emr.getSignature(), "signedAt", emr.getSignedAt()));
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    public record CreateOrdersRequest(List<DoctorStationService.OrderLine> lines) {}

    @PostMapping("/{registrationId}/orders")
    public R<Object> createOrders(@PathVariable Long registrationId, @RequestBody CreateOrdersRequest req,
                                  Authentication auth) {
        try {
            return R.ok(doctorStationService.createOrders(registrationId, req.lines(), currentUserService.idOf(auth)));
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @PutMapping("/orders/{orderId}/cancel")
    public R<Void> cancelOrder(@PathVariable Long orderId) {
        try {
            doctorStationService.cancelOrder(orderId);
            return R.ok();
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }
}
