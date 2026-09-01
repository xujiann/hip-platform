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

    // ===================== v42 车道1：体温单（三测单）出纸数据集 =====================

    /** 三测单纸面标准 6 时点列：2/6/10/14/18/22 时 */
    private static final int[] SHEET_SLOT_HOURS = {2, 6, 10, 14, 18, 22};

    private static final java.time.ZoneId ZONE =
            java.time.ZoneId.of(cn.hip.platform.core.config.HipProfiles.ZONE);

    /**
     * v42：体温单（三测单）打印数据集——页眉 + 该住院周 7 天的逐日格点。
     *
     * <p><b>周窗口口径</b>：week 自 1 起，第 N 周 = 入院当日（按业务时区 Asia/Shanghai
     * 取日期）+ (N-1)*7 天起的 7 个自然日，<b>不按自然周（周一起）对齐</b>——纸面三测单
     * 一律以入院日为第 1 住院日起排。总周数按出院日（未出院按今天）推算，越界返 4821。
     *
     * <p><b>出入量口径（定死，勿改）</b>：inp_icu_record 与 inp_vital_sign 两表<b>绝不合并</b>
     * （ICU 有独立写路径与 gcs/ventilator 独有语义）。合并只发生在<b>读侧、且只在本端点内</b>，
     * 按日、按指标分别取：同日有 ICU 记录则 ICU 优先，否则用体征记录新列汇总。返回体每日带
     * intakeSource / outputSource（ICU / VITAL / null），使纸面与页面能标注数据来源。
     *
     * <p><b>口径近似（须在 CHANGELOG 明示）</b>：
     * <ul>
     *   <li>时点归格按"同日最近标准时点"四舍五入（00:30 归入当日 2 时列、23:30 归入 22 时列），
     *       <b>不跨日归格</b>——避免把次日凌晨的记录画到前一日末列；</li>
     *   <li>大便次数/体重/身高取当日<b>最后一条非空值</b>（护理惯例为当日累计值覆盖录入），不求和；</li>
     *   <li>出入量在同一来源内按当日<b>求和</b>。</li>
     * </ul>
     */
    @GetMapping("/print/temp-sheet")
    public R<java.util.Map<String, Object>> printTempSheet(@PathVariable Long admissionId,
                                                           @RequestParam(defaultValue = "1") int week) {
        var head = jdbc.queryForList("""
                select a.admission_no, a.admit_at, a.discharged_at, a.status, a.care_level,
                       p.name as patient_name, p.patient_no, p.sex, p.birth_date, p.allergy_history,
                       cd.name as dept_name, wd.name as ward_name, b.bed_no
                from inp_admission a
                join empi_patient p on p.id = a.patient_id
                left join sys_dept cd on cd.id = a.dept_id
                left join sys_dept wd on wd.id = a.ward_id
                left join inp_bed  b  on b.id = a.bed_id
                where a.id = ?
                """, admissionId);
        if (head.isEmpty()) return R.fail(4820, "住院记录不存在");
        var h = head.get(0);

        java.time.LocalDate admitDate = toLocalDate(h.get("admit_at"));
        if (admitDate == null) return R.fail(4820, "住院记录缺入院时间，无法排布体温单周次");
        java.time.LocalDate endDate = toLocalDate(h.get("discharged_at"));
        if (endDate == null) endDate = cn.hip.platform.core.config.BusinessDates.today();
        if (endDate.isBefore(admitDate)) endDate = admitDate;
        int totalWeeks = (int) (java.time.temporal.ChronoUnit.DAYS.between(admitDate, endDate) / 7) + 1;
        if (week < 1 || week > totalWeeks) {
            return R.fail(4821, "住院周次越界：本次住院共 " + totalWeeks + " 周，请求第 " + week + " 周");
        }

        java.time.LocalDate weekStart = admitDate.plusDays((week - 1) * 7L);
        java.time.LocalDate weekEnd = weekStart.plusDays(6);
        java.time.Instant from = weekStart.atStartOfDay(ZONE).toInstant();
        java.time.Instant to = weekEnd.plusDays(1).atStartOfDay(ZONE).toInstant();

        // 体征格点（周窗口半开区间，走 idx_inp_vital_adm_time）
        List<InpVitalSign> vitals = vitalRepo
                .findByAdmissionIdAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtAsc(
                        admissionId, from, to);

        // ICU 记录的出入量日汇总——只读侧合并，两表不合并
        // 时区写死为业务时区常量（非用户输入，无注入面）：AT TIME ZONE 的 zone 位置用绑定参数时
        // PG 无法推断参数类型（could not determine data type），且此处口径必须与 Java 侧 ZONE 同源。
        String tz = "'" + ZONE.getId() + "'";
        var icuRows = jdbc.queryForList("""
                select (r.recorded_at at time zone %1$s)::date as d,
                       sum(r.intake_ml) as intake_ml, sum(r.output_ml) as output_ml,
                       count(*) as rec_count
                from inp_icu_record r
                where r.admission_id = ?
                  and r.recorded_at >= (?::date at time zone %1$s)
                  and r.recorded_at <  ((?::date + interval '1 day') at time zone %1$s)
                group by 1
                """.formatted(tz), admissionId, weekStart.toString(), weekEnd.toString());
        var icuByDay = new java.util.HashMap<java.time.LocalDate, java.util.Map<String, Object>>();
        for (var r : icuRows) {
            java.time.LocalDate d = toLocalDate(r.get("d"));
            if (d != null) icuByDay.put(d, r);
        }

        var byDay = new java.util.LinkedHashMap<java.time.LocalDate, List<InpVitalSign>>();
        for (int i = 0; i < 7; i++) byDay.put(weekStart.plusDays(i), new java.util.ArrayList<>());
        for (InpVitalSign v : vitals) {
            var bucket = byDay.get(v.getMeasuredAt().atZone(ZONE).toLocalDate());
            if (bucket != null) bucket.add(v);
        }

        var days = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (var e : byDay.entrySet()) {
            days.add(buildSheetDay(e.getKey(), e.getValue(), icuByDay.get(e.getKey()), admitDate));
        }

        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("header", h);
        m.put("week", week);
        m.put("totalWeeks", totalWeeks);
        m.put("weekStart", weekStart.toString());
        m.put("weekEnd", weekEnd.toString());
        m.put("slotHours", java.util.Arrays.stream(SHEET_SLOT_HOURS).boxed().toList());
        m.put("days", days);
        return R.ok(m);
    }

    /** 单日格位：时点数据 + 日汇总（出入量按来源优先级合并，ICU 优先） */
    private java.util.Map<String, Object> buildSheetDay(java.time.LocalDate date, List<InpVitalSign> rows,
                                                        java.util.Map<String, Object> icu,
                                                        java.time.LocalDate admitDate) {
        var points = new java.util.ArrayList<java.util.Map<String, Object>>();
        Integer stool = null;
        Integer height = null;
        java.math.BigDecimal weight = null;
        long vitalIntake = 0;
        long vitalOutput = 0;
        boolean hasVitalIntake = false;
        boolean hasVitalOutput = false;
        for (InpVitalSign v : rows) {
            var zt = v.getMeasuredAt().atZone(ZONE);
            int slot = slotOf(zt.getHour(), zt.getMinute());
            var p = new java.util.LinkedHashMap<String, Object>();
            p.put("measuredAt", v.getMeasuredAt().toString());
            p.put("slot", slot);
            p.put("slotHour", SHEET_SLOT_HOURS[slot]);
            p.put("temperature", v.getTemperature());
            p.put("tempAfterCooling", v.getTempAfterCooling());
            p.put("pulse", v.getPulse());
            p.put("respiration", v.getRespiration());
            p.put("sbp", v.getSbp());
            p.put("dbp", v.getDbp());
            p.put("spo2", v.getSpo2());
            p.put("measureSite", v.getMeasureSite());
            p.put("notMeasuredReason", v.getNotMeasuredReason());
            points.add(p);
            if (v.getStoolCount() != null) stool = v.getStoolCount();
            if (v.getHeightCm() != null) height = v.getHeightCm();
            if (v.getWeightKg() != null) weight = v.getWeightKg();
            if (v.getIntakeMl() != null) {
                vitalIntake += v.getIntakeMl();
                hasVitalIntake = true;
            }
            if (v.getOutputMl() != null) {
                vitalOutput += v.getOutputMl();
                hasVitalOutput = true;
            }
        }

        Number icuIntake = icu == null ? null : (Number) icu.get("intake_ml");
        Number icuOutput = icu == null ? null : (Number) icu.get("output_ml");
        // 三元套三元会因 long 分支触发自动拆箱、null 分支必 NPE——此处显式 if/else，勿改回三元
        Long intake = null;
        String intakeSource = null;
        if (icuIntake != null) {
            intake = icuIntake.longValue();
            intakeSource = "ICU";
        } else if (hasVitalIntake) {
            intake = vitalIntake;
            intakeSource = "VITAL";
        }
        Long output = null;
        String outputSource = null;
        if (icuOutput != null) {
            output = icuOutput.longValue();
            outputSource = "ICU";
        } else if (hasVitalOutput) {
            output = vitalOutput;
            outputSource = "VITAL";
        }

        var d = new java.util.LinkedHashMap<String, Object>();
        d.put("date", date.toString());
        d.put("hospitalDay", (int) java.time.temporal.ChronoUnit.DAYS.between(admitDate, date) + 1);
        d.put("points", points);
        d.put("intakeMl", intake);
        d.put("outputMl", output);
        d.put("intakeSource", intakeSource);
        d.put("outputSource", outputSource);
        d.put("stoolCount", stool);
        d.put("weightKg", weight);
        d.put("heightCm", height);
        return d;
    }

    /** 归入同日最近的标准时点列（不跨日归格，见 printTempSheet javadoc 的口径近似说明） */
    private static int slotOf(int hour, int minute) {
        long idx = Math.round((hour + minute / 60.0 - SHEET_SLOT_HOURS[0]) / 4.0);
        return (int) Math.min(SHEET_SLOT_HOURS.length - 1L, Math.max(0L, idx));
    }

    /** JDBC 时间列（Timestamp / Date / OffsetDateTime）统一转业务时区日期 */
    private static java.time.LocalDate toLocalDate(Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant().atZone(ZONE).toLocalDate();
        if (v instanceof java.time.Instant i) return i.atZone(ZONE).toLocalDate();
        if (v instanceof java.time.OffsetDateTime o) return o.atZoneSameInstant(ZONE).toLocalDate();
        if (v instanceof java.time.LocalDateTime l) return l.toLocalDate();
        if (v instanceof java.time.LocalDate l) return l;
        return java.time.LocalDate.parse(String.valueOf(v).substring(0, 10));
    }
}
