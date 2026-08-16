package cn.hip.inpatient.web;

import cn.hip.inpatient.entity.InpMedicalRecord;
import cn.hip.inpatient.entity.InpVitalSign;
import cn.hip.inpatient.repository.MedicalRecordRepo;
import cn.hip.inpatient.repository.VitalSignRepo;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 住院病历与生命体征 */
@RestController
@RequestMapping("/api/inpatient/admissions/{admissionId}")
@PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE')")   // 1.0.9：权限清点补齐
@RequiredArgsConstructor
public class InpEmrController {

    private final MedicalRecordRepo recordRepo;
    private final VitalSignRepo vitalRepo;
    private final CurrentUserService currentUserService;
    private final cn.hip.platform.integration.signature.SignatureAdapter signatureAdapter;

    @GetMapping("/records")
    public R<List<InpMedicalRecord>> records(@PathVariable Long admissionId) {
        return R.ok(recordRepo.findByAdmissionIdOrderByIdDesc(admissionId));
    }

    public record SaveRecordRequest(String recordType, String title, String content) {}

    @PostMapping("/records")
    public R<InpMedicalRecord> addRecord(@PathVariable Long admissionId,
                                         @RequestBody SaveRecordRequest req, Authentication auth) {
        if (req.content() == null || req.content().isBlank()) {
            return R.fail(9101, "病历内容不能为空");
        }
        InpMedicalRecord r = new InpMedicalRecord();
        r.setAdmissionId(admissionId);
        r.setRecordType(req.recordType() == null ? "PROGRESS" : req.recordType());
        r.setTitle(req.title() == null || req.title().isBlank() ? "病程记录" : req.title());
        r.setContent(req.content());
        r.setDoctorId(currentUserService.idOf(auth));
        return R.ok(recordRepo.save(r));
    }

    /** 1.0.4：住院病历 CA 签名（SignatureAdapter，与门诊同语义；已签名不可重签） */
    @PostMapping("/records/{recordId}/sign")
    public R<java.util.Map<String, Object>> signRecord(@PathVariable Long admissionId,
                                                       @PathVariable Long recordId, Authentication auth) {
        InpMedicalRecord r = recordRepo.findById(recordId)
                .filter(x -> x.getAdmissionId().equals(admissionId)).orElse(null);
        if (r == null) return R.fail(9102, "病历不存在");
        if (r.getSignature() != null) return R.fail(9103, "病历已签名");
        var result = signatureAdapter.sign(r.getContent(), currentUserService.idOf(auth));
        if (!result.ok()) return R.fail(9104, "签名失败: " + result.message());
        r.setSignature(result.signature());
        r.setSignedAt(java.time.Instant.now());
        recordRepo.save(r);
        return R.ok(java.util.Map.of("signature", r.getSignature(), "signedAt", r.getSignedAt()));
    }

    @GetMapping("/vitals")
    public R<List<InpVitalSign>> vitals(@PathVariable Long admissionId) {
        return R.ok(vitalRepo.findByAdmissionIdOrderByMeasuredAtAsc(admissionId));
    }

    @PostMapping("/vitals")
    public R<InpVitalSign> addVital(@PathVariable Long admissionId,
                                    @RequestBody InpVitalSign vital, Authentication auth) {
        vital.setId(null);
        vital.setAdmissionId(admissionId);
        if (vital.getMeasuredAt() == null) {
            vital.setMeasuredAt(java.time.Instant.now());
        }
        vital.setRecorderId(currentUserService.idOf(auth));
        return R.ok(vitalRepo.save(vital));
    }
}
