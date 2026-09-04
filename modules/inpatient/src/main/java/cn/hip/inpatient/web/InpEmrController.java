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
    private final cn.hip.inpatient.service.VitalValidator vitalValidator;
    private final cn.hip.platform.integration.signature.SignatureAdapter signatureAdapter;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @GetMapping("/records")
    public R<List<InpMedicalRecord>> records(@PathVariable Long admissionId) {
        return R.ok(recordRepo.findByAdmissionIdOrderByIdDesc(admissionId));
    }

    /**
     * 住院病历保存请求体。
     *
     * <p>v45 车道 I 追加两个<b>可空</b>参数 {@code templateId} / {@code fields}（989★/1075★）——
     * 与门诊侧同口径：<b>不新建"结构化病历"端点</b>，只给既有端点加可空入参。
     * 旧请求体反序列化后两者为 null，整段结构化逻辑短路，落库与返回体逐字节不变；
     * 三参构造器保留是为让既有单测（V42EmrTemplateTest:74,98）源码零改动。
     */
    public record SaveRecordRequest(String recordType, String title, String content,
                                    Long templateId, java.util.Map<String, Object> fields) {
        /** v45 之前的三参形态 */
        public SaveRecordRequest(String recordType, String title, String content) {
            this(recordType, title, content, null, null);
        }
    }

    /**
     * v47：住院病历类型白名单。
     *
     * <p>此前 {@code req.recordType()} 是<b>任意字符串直落库</b>，而下游
     * {@link cn.hip.inpatient.service.EmrIntegrityService} 与病历时限质控<b>全是精确等值判定</b>
     * （{@code record_type = 'ADMISSION'} / {@code in ('PROGRESS','ROUND','FIRST_PROGRESS')} …），
     * 写错一个字母就 <b>100% 漏判且零报错</b>——病历完整性看着"通过"，其实那条记录谁也统计不到。
     *
     * <p><b>刻意不给 record_type 加数据库 CHECK</b>（v42 已定此口径）：试点库的历史脏类型
     * 会挡住 Flyway，迁移失败的代价远高于脏数据。收口只做在写入端。
     */
    private static final List<String> RECORD_TYPES =
            List.of("ADMISSION", "FIRST_PROGRESS", "PROGRESS", "ROUND", "DISCHARGE", "PREOP");

    @PostMapping("/records")
    public R<InpMedicalRecord> addRecord(@PathVariable Long admissionId,
                                         @RequestBody SaveRecordRequest req, Authentication auth) {
        // v47：**不传仍默认 PROGRESS（既有行为逐字不动）**，只有显式传了非白名单值才拒。
        // 挡在结构化渲染之前，避免类型非法时先报模板校验码（4024–4027）造成误导。
        if (req.recordType() != null && !RECORD_TYPES.contains(req.recordType())) {
            return R.fail(9129, "病历类型非法：" + req.recordType()
                    + "（合法值 " + String.join("/", RECORD_TYPES) + "）");
        }
        String content = req.content();
        String contentJson = null;
        // v45 车道 I：结构化录入。fields == null 时整段跳过，下面就是 v44 的原逻辑一字未改。
        if (req.fields() != null) {
            StructuredEmr.Rendered out;
            try {
                out = StructuredEmr.validateAndRender(
                        StructuredEmr.load(jdbc, req.templateId()), req.fields(), objectMapper);
            } catch (StructuredEmr.StructuredError e) {
                return R.fail(e.code, e.getMessage());
            }
            // **先渲染正文再存侧车**：住院签名摘要就是 content 原文（见 signRecord），
            // 只写 content_json 不渲染正文等于让 CA 签了个空壳。content 是 text（V133），无长度上限。
            content = StructuredEmr.merge(content, out.text());
            contentJson = out.json();
        }
        if (content == null || content.isBlank()) {
            return R.fail(9101, "病历内容不能为空");
        }
        InpMedicalRecord r = new InpMedicalRecord();
        r.setAdmissionId(admissionId);
        r.setRecordType(req.recordType() == null ? "PROGRESS" : req.recordType());
        r.setTitle(req.title() == null || req.title().isBlank() ? "病程记录" : req.title());
        r.setContent(content);
        r.setContentJson(contentJson);
        r.setTemplateId(req.fields() == null ? null : req.templateId());
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

    /**
     * v47 写入校验收口后的返回体：<b>仅在有告警时</b>用这个子类回带 {@code warnings}。
     *
     * <p>没有告警时照旧返回裸的 {@link InpVitalSign}，返回体<b>逐字节</b>与 v46 相同——
     * 存量对接方（e2e-phase3234 等）不会因为多出一个 null 字段而变形。
     * 用子类而不是换成 Map，是为了让既有调用方的 {@code getData().getId()} 源码零改动。
     */
    public static class VitalSaved extends InpVitalSign {
        private final List<String> warnings;

        private VitalSaved(InpVitalSign saved, List<String> warnings) {
            org.springframework.beans.BeanUtils.copyProperties(saved, this);
            this.warnings = warnings;
        }

        public List<String> getWarnings() {
            return warnings;
        }
    }

    /**
     * 录一条生命体征。
     *
     * <p><b>v47 起有服务端校验</b>（此前是 {@code setId(null)} 后直接 save 的零校验写路径）：
     * 六项量程 4824 / 测量时间 4825 / 测量部位 4822 / 未测原因 4823，
     * 三态 gate {@code emr.gate.vital.range} <b>默认 warn</b>——
     * warn 档<b>照常落库</b>只回带 warnings（护士当场看见"体温 999 已记录，请核对"），
     * block 档才拒。判定规则与默认档位的理由见 {@link cn.hip.inpatient.service.VitalValidator}。
     *
     * <p>合法值下这个方法与 v46 行为完全一致：落库字段、measuredAt 缺省补 now()、返回体全都不变。
     */
    @PostMapping("/vitals")
    public R<InpVitalSign> addVital(@PathVariable Long admissionId,
                                    @RequestBody InpVitalSign vital, Authentication auth) {
        vital.setId(null);
        vital.setAdmissionId(admissionId);

        // 校验在补 now() **之前**做：measuredAt 为 null 是"这次不传"，由服务端补当前时刻，
        // 天然合法；补完再校验等于永远校验不到调用方真正传了什么。
        var violations = vitalValidator.validate(admissionId, vital);
        if (!violations.isEmpty() && "block".equals(vitalValidator.gate())) {
            return R.fail(violations.get(0).code(), violations.get(0).message());
        }

        if (vital.getMeasuredAt() == null) {
            vital.setMeasuredAt(java.time.Instant.now());
        }
        vital.setRecorderId(currentUserService.idOf(auth));
        InpVitalSign saved = vitalRepo.save(vital);
        if (violations.isEmpty()) return R.ok(saved);   // 无告警：返回体逐字节不变
        return R.ok(new VitalSaved(saved,
                violations.stream().map(cn.hip.inpatient.service.VitalValidator.Violation::message).toList()));
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

    // ==================== v45 车道 I：结构化字段录入（989★/1075★/1098★） ====================

    /**
     * 结构化元素的定义装载、校验、渲染与序列化。
     *
     * <p><b>刻意与 {@code DoctorStationService.StructuredEmr} 逐行同源的一份复制</b>，原因是模块依赖：
     * hip-inpatient 与 hip-outpatient 的唯一公共依赖是 hip-platform-core，而本车道不持有
     * platform/core 的改动权（v45 车道纪律：一个文件只有一个车道能改）。下沉到 platform/core
     * 属跨车道动作，记入技术债在合版后统一做。两份代码的行为由同一批单测
     * （V45StructuredEmrTest 的门诊/住院两组对称用例）钉死，任何一侧漂移都会先红。
     *
     * <p>唯一的实现差异：门诊侧直接抛模块内既有的 {@code BizException}，住院侧本模块无同类基类，
     * 故用下面这个只带错误码的 {@link StructuredError}，由 {@code addRecord} 转成 {@code R.fail}。
     *
     * <p>错误码：4024 字段定义不存在或已停用 / 4025 必填未填 / 4026 取值不在值域 / 4027 类型不匹配。
     */
    static final class StructuredEmr {

        /** 结构化校验失败（只带业务码，调用方转 R.fail；不做 GlobalExceptionHandler 兜底） */
        static final class StructuredError extends RuntimeException {
            final int code;

            StructuredError(int code, String message) {
                super(message);
                this.code = code;
            }
        }

        /** 1075★ 明文六型，一个不少一个不多（与 V139 的 CHECK 约束同源） */
        static final List<String> DATATYPES = List.of("TEXT", "NUMBER", "CHECKBOX", "RADIO", "MULTI", "DATE");

        /** 渲染块的包裹标记：写入前先剥旧块再追加新块，保证"表单→正文"往返幂等 */
        static final String BLOCK_BEGIN = "【结构化记录】";
        static final String BLOCK_END = "【结构化记录结束】";
        private static final java.util.regex.Pattern BLOCK =
                java.util.regex.Pattern.compile("\\n?" + BLOCK_BEGIN + ".*?" + BLOCK_END + "\\n?",
                        java.util.regex.Pattern.DOTALL);

        /** 单个 TEXT 元素的长度上限（与门诊侧同值，两侧口径必须一致） */
        static final int TEXT_MAX = 1000;

        /** value_set 解析专用（只读 JSON 数组，无需 Spring 的定制配置），避免逐行 new */
        private static final com.fasterxml.jackson.databind.ObjectMapper VALUE_SET_MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();

        private StructuredEmr() {}

        /** 一条字段定义（只取启用中的） */
        record FieldDef(long id, String fieldCode, String label, String datatype,
                        boolean required, List<String> valueSet, String unit) {}

        /** 渲染产物：{@code text} 进正文（参与签名）、{@code json} 进 content_json 侧车 */
        record Rendered(String text, String json) {}

        /** 取模板下启用中的字段定义，按 sort_no 排（sort_no 同时是 989★ 快速跳转的 Tab 序） */
        static List<FieldDef> load(org.springframework.jdbc.core.JdbcTemplate jdbc, Long templateId) {
            if (templateId == null) {
                throw new StructuredError(4024, "未指定病历模板，无法解析结构化字段（templateId 必填）");
            }
            var rows = jdbc.queryForList("""
                    select id, field_code, label, datatype, required, value_set, unit
                    from emr_template_field
                    where template_id = ? and enabled = true
                    order by sort_no, id
                    """, templateId);
            return rows.stream().map(r -> new FieldDef(
                    ((Number) r.get("id")).longValue(),
                    (String) r.get("field_code"),
                    (String) r.get("label"),
                    (String) r.get("datatype"),
                    Boolean.TRUE.equals(r.get("required")),
                    parseValueSet((String) r.get("value_set")),
                    (String) r.get("unit"))).toList();
        }

        /** value_set 是 text 存的 JSON 数组（本仓惯例）；解析失败按"未配置候选值"处理，由 4026 兜住 */
        static List<String> parseValueSet(String raw) {
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            try {
                var node = VALUE_SET_MAPPER.readTree(raw);
                if (!node.isArray()) {
                    return List.of();
                }
                var out = new java.util.ArrayList<String>();
                node.forEach(n -> {
                    String v = n.asText();
                    if (v != null && !v.isBlank()) {
                        out.add(v.trim());
                    }
                });
                return List.copyOf(out);
            } catch (Exception e) {
                return List.of();
            }
        }

        /**
         * 逐字段校验 + 渲染 + 序列化。
         *
         * <p>顺序刻意是「先认全部键、再逐字段校验」：传了一个模板里没有的 fieldCode 时先报 4024，
         * 而不是被必填检查抢先报 4025——前者才是真正的错因（多半是模板选错了）。
         */
        static Rendered validateAndRender(List<FieldDef> defs, java.util.Map<String, Object> fields,
                                          com.fasterxml.jackson.databind.ObjectMapper mapper) {
            var byCode = new java.util.LinkedHashMap<String, FieldDef>();
            for (FieldDef d : defs) {
                byCode.put(d.fieldCode(), d);
            }
            for (String k : fields.keySet()) {
                if (!byCode.containsKey(k)) {
                    throw new StructuredError(4024, "模板字段定义不存在或已停用：" + k);
                }
            }
            var values = new java.util.LinkedHashMap<String, Object>();
            var lines = new java.util.ArrayList<String>();
            for (FieldDef d : defs) {
                Object raw = fields.get(d.fieldCode());
                if (isEmpty(raw)) {
                    if (d.required()) {
                        throw new StructuredError(4025,
                                "必填结构化字段未填：" + d.label() + "（" + d.fieldCode() + "）");
                    }
                    continue;   // 未填的可选字段既不进侧车也不进正文——不写空行、不编造默认值
                }
                Object v = coerce(d, raw);
                values.put(d.fieldCode(), v);
                lines.add(d.label()
                        + (d.unit() == null || d.unit().isBlank() ? "" : "（" + d.unit() + "）")
                        + "：" + display(d, v));
            }
            String text = lines.isEmpty() ? "" : BLOCK_BEGIN + "\n" + String.join("\n", lines) + "\n" + BLOCK_END;
            try {
                return new Rendered(text, mapper.writeValueAsString(values));
            } catch (Exception e) {
                throw new StructuredError(4027, "结构化字段序列化失败：" + e.getMessage());
            }
        }

        private static boolean isEmpty(Object raw) {
            if (raw == null) {
                return true;
            }
            if (raw instanceof String s) {
                return s.isBlank();
            }
            return raw instanceof java.util.Collection<?> c && c.isEmpty();
        }

        /** 按 datatype 把前端传来的原始值收敛成 JSON 原生型；不匹配一律 4027，值域不符 4026 */
        static Object coerce(FieldDef d, Object raw) {
            String s = raw instanceof String str ? str.trim() : String.valueOf(raw);
            switch (d.datatype()) {
                case "TEXT" -> {
                    if (raw instanceof java.util.Collection<?> || raw instanceof java.util.Map<?, ?>) {
                        throw typeErr(d, raw);
                    }
                    if (s.length() > TEXT_MAX) {
                        throw new StructuredError(4027, "字段「" + d.label() + "」文本超过 " + TEXT_MAX + " 字上限");
                    }
                    return s;
                }
                case "NUMBER" -> {
                    try {
                        return new java.math.BigDecimal(s);
                    } catch (NumberFormatException e) {
                        throw typeErr(d, raw);
                    }
                }
                case "CHECKBOX" -> {
                    if (raw instanceof Boolean b) {
                        return b;
                    }
                    return switch (s) {
                        case "true", "1", "是", "Y", "y" -> Boolean.TRUE;
                        case "false", "0", "否", "N", "n" -> Boolean.FALSE;
                        default -> throw typeErr(d, raw);
                    };
                }
                case "DATE" -> {
                    try {
                        return java.time.LocalDate.parse(s).toString();
                    } catch (Exception e) {
                        throw typeErr(d, raw);
                    }
                }
                case "RADIO" -> {
                    if (raw instanceof java.util.Collection<?> || raw instanceof java.util.Map<?, ?>) {
                        throw typeErr(d, raw);
                    }
                    requireInValueSet(d, s);
                    return s;
                }
                case "MULTI" -> {
                    List<String> picked = toList(d, raw);
                    picked.forEach(x -> requireInValueSet(d, x));
                    return picked;
                }
                default -> throw new StructuredError(4027,
                        "字段「" + d.label() + "」数据类型非法：" + d.datatype() + "（只接受 " + DATATYPES + "）");
            }
        }

        /** MULTI 兼容两种回传形态：JSON 数组，或以 , ; 、 分隔的字符串（前端老表单常见） */
        private static List<String> toList(FieldDef d, Object raw) {
            if (raw instanceof java.util.Collection<?> c) {
                var out = new java.util.ArrayList<String>();
                for (Object o : c) {
                    if (o instanceof java.util.Collection<?> || o instanceof java.util.Map<?, ?>) {
                        throw typeErr(d, raw);
                    }
                    String v = String.valueOf(o).trim();
                    if (!v.isEmpty() && !out.contains(v)) {
                        out.add(v);
                    }
                }
                return out;
            }
            if (raw instanceof java.util.Map<?, ?>) {
                throw typeErr(d, raw);
            }
            var out = new java.util.ArrayList<String>();
            for (String v : String.valueOf(raw).split("[,;、]")) {
                String t = v.trim();
                if (!t.isEmpty() && !out.contains(t)) {
                    out.add(t);
                }
            }
            return out;
        }

        private static void requireInValueSet(FieldDef d, String v) {
            if (d.valueSet().isEmpty()) {
                throw new StructuredError(4026, "字段「" + d.label() + "」未配置候选值，无法校验取值");
            }
            if (!d.valueSet().contains(v)) {
                throw new StructuredError(4026, "字段「" + d.label() + "」取值不在值域内：" + v
                        + "（候选：" + String.join("/", d.valueSet()) + "）");
            }
        }

        private static StructuredError typeErr(FieldDef d, Object raw) {
            return new StructuredError(4027, "字段「" + d.label() + "」数据类型不匹配："
                    + raw + " 不是合法的 " + d.datatype());
        }

        /** 正文里的显示形态：复选渲染成是/否、多选顿号连接——正文要给人读，不是给机器读 */
        static String display(FieldDef d, Object v) {
            if (v instanceof Boolean b) {
                return b ? "是" : "否";
            }
            if (v instanceof java.util.Collection<?> c) {
                return c.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("、"));
            }
            return String.valueOf(v);
        }

        /** 剥掉正文里已有的结构化块（幂等的前提：前端回传的正文含上一次渲染结果） */
        static String strip(String body) {
            return body == null ? null : BLOCK.matcher(body).replaceAll("").stripTrailing();
        }

        /** 医生手打正文 + 结构化块 = 送去签名的那份全文 */
        static String merge(String body, String block) {
            String kept = strip(body);
            if (block == null || block.isEmpty()) {
                return kept;
            }
            return kept == null || kept.isBlank() ? block : kept + "\n" + block;
        }
    }
}
