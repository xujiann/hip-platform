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
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

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

    public record RoundRequest(String roundLevel, String roundOpinion, String superiorCorrection, String title) {}

    /**
     * v34 三级查房结构化记录（主任 CHIEF / 主治 ATTENDING / 住院医 RESIDENT 查房）。
     * 复用 record_type='ROUND' 存同表，签名冻结/补正/病历列表/复印/CDR/首页泛型读取自动纳入。
     * 是病历时限质控"查房时限"统计的数据来源。
     */
    @PostMapping("/records/round")
    public R<InpMedicalRecord> addRound(@PathVariable Long admissionId,
                                        @RequestBody RoundRequest req, Authentication auth) {
        if (req.roundLevel() == null || !List.of("CHIEF", "ATTENDING", "RESIDENT").contains(req.roundLevel())) {
            return R.fail(9119, "查房级别非法（CHIEF 主任 / ATTENDING 主治 / RESIDENT 住院医）");
        }
        if (req.roundOpinion() == null || req.roundOpinion().isBlank()) {
            return R.fail(9120, "查房意见不能为空");
        }
        String levelCn = switch (req.roundLevel()) {
            case "CHIEF" -> "主任";
            case "ATTENDING" -> "主治";
            default -> "住院医";
        };
        Long me = currentUserService.idOf(auth);
        InpMedicalRecord r = new InpMedicalRecord();
        r.setAdmissionId(admissionId);
        r.setRecordType("ROUND");
        r.setTitle(req.title() == null || req.title().isBlank() ? "三级查房记录·" + levelCn + "查房" : req.title());
        r.setContent(req.roundOpinion());   // content not null，复用查房意见作正文
        r.setDoctorId(me);
        r.setRoundLevel(req.roundLevel());
        r.setRoundDoctorId(me);
        r.setRoundOpinion(req.roundOpinion());
        r.setSuperiorCorrection(req.superiorCorrection());
        return R.ok(recordRepo.save(r));
    }

    /** 查房记录列表（可按级别过滤），含查房医师姓名与是否已签名冻结 */
    @GetMapping("/records/rounds")
    public R<List<java.util.Map<String, Object>>> rounds(@PathVariable Long admissionId,
                                                         @RequestParam(required = false) String level) {
        String sql = "select r.id, r.round_level, r.round_opinion, r.superior_correction, r.created_at, "
                + "r.round_doctor_id, u.real_name as round_doctor_name, (r.signature is not null) as signed "
                + "from inp_medical_record r left join sys_user u on u.id = r.round_doctor_id "
                + "where r.admission_id = ? and r.record_type = 'ROUND' "
                + (level == null ? "" : " and r.round_level = ? ") + " order by r.id";
        return R.ok(level == null ? jdbc.queryForList(sql, admissionId) : jdbc.queryForList(sql, admissionId, level));
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

    public record AmendRequest(String amendText, String reason) {}

    /**
     * 1.2.13 阻塞4：住院病历补正——签名冻结的病历不放开编辑，只能追加法定留痕补正记录
     * （原文快照 + 补正内容 + 补正人 + 补正时间 + 补正原因）。
     * 签名前应走 POST /records 或直接维护，签名后才走补正。
     */
    @PostMapping("/records/{recordId}/amend")
    public R<Void> amendRecord(@PathVariable Long admissionId, @PathVariable Long recordId,
                               @RequestBody AmendRequest req, Authentication auth) {
        InpMedicalRecord r = recordRepo.findById(recordId)
                .filter(x -> x.getAdmissionId().equals(admissionId)).orElse(null);
        if (r == null) return R.fail(9107, "病历不存在");
        if (r.getSignature() == null) return R.fail(9108, "病历未签名冻结，请直接修改，无需补正");
        if (req.amendText() == null || req.amendText().isBlank()) return R.fail(9109, "补正内容不能为空");
        if (req.reason() == null || req.reason().isBlank()) return R.fail(9109, "补正原因不能为空");
        jdbc.update("""
                insert into emr_amendment(emr_type, emr_id, original_text, amend_text, reason, amended_by)
                values ('INP', ?, ?, ?, ?, ?)
                """, r.getId(), r.getContent(), req.amendText(), req.reason(), currentUserService.idOf(auth));
        return R.ok();
    }

    /** 住院病历补正历史（时间正序） */
    @GetMapping("/records/{recordId}/amendments")
    public R<List<java.util.Map<String, Object>>> amendments(@PathVariable Long admissionId,
                                                             @PathVariable Long recordId) {
        return R.ok(jdbc.queryForList("""
                select a.id, a.amend_text, a.reason, a.amended_by, a.amended_at, u.real_name as amended_by_name
                from emr_amendment a left join sys_user u on u.id = a.amended_by
                where a.emr_type = 'INP' and a.emr_id = ?
                order by a.id
                """, recordId));
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
