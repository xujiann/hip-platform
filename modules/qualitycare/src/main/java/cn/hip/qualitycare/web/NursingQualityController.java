package cn.hip.qualitycare.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 九期：护理白板、护理级别、病历时限质控、病案首页/归档、不良事件、院感登记 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','NURSE','QUALITY')")   // 1.0.9：权限清点补齐
@RequiredArgsConstructor
public class NursingQualityController {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;
    private final cn.hip.platform.core.service.ConfigReader configReader;
    private final cn.hip.inpatient.service.EmrIntegrityService emrIntegrityService;

    /** 护理白板：在院一览（床位/护理级别/过敏/未执行医嘱数） */
    @GetMapping("/api/inpatient/nursing/board")
    public R<List<Map<String, Object>>> board() {
        return R.ok(jdbc.queryForList("""
                select a.id as admission_id, a.admission_no, a.care_level,
                       w.name as ward_name, b.bed_no, d.name as dept_name,
                       p.name as patient_name, p.sex, p.allergy_history,
                       (select count(*) from inp_order o where o.admission_id = a.id and o.status = 'CREATED') as pending_orders
                from inp_admission a
                join inp_bed b on b.id = a.bed_id
                join sys_dept w on w.id = a.ward_id
                join sys_dept d on d.id = a.dept_id
                join empi_patient p on p.id = a.patient_id
                where a.status = 'IN_HOSPITAL'
                order by w.name, b.bed_no
                """));
    }

    @PutMapping("/api/inpatient/admissions/{id}/care-level")
    public R<Void> setCareLevel(@PathVariable Long id, @RequestParam String level) {
        if (!List.of("特级", "一级", "二级", "三级").contains(level)) return R.fail(9801, "非法护理级别");
        jdbc.update("update inp_admission set care_level = ? where id = ?", level, id);
        return R.ok();
    }

    /**
     * 病历时限质控（v34 扩至 7 项）：入院&gt;24h 无入院记录 / 出院无出院小结 / 门诊未签名（原 3 项）
     * + 首程&gt;8h / 三级查房&gt;48h（依赖 ward-round，emr.round.check.enabled=on 才查）/ 病程连续性 / 抢救记录超时闭合。
     * 阈值均 sys_config 可配。纯只读报表，不 gate 任何写路径。
     */
    @GetMapping("/api/quality/emr-timeliness")
    public R<Map<String, Object>> emrTimeliness() {
        int firstProgressH = configReader.getInt("emr.timeliness.first_progress_hours", 8);
        int roundH = configReader.getInt("emr.timeliness.round_interval_hours", 48);
        int gapDays = configReader.getInt("emr.timeliness.progress_gap_days", 3);
        int gapCritDays = configReader.getInt("emr.timeliness.progress_gap_crit_days", 1);
        int rescueH = configReader.getInt("emr.timeliness.rescue_record_hours", 6);
        boolean roundCheck = "on".equalsIgnoreCase(configReader.get("emr.timeliness.round_check.enabled", "off"));

        var missingAdmission = jdbc.queryForList("""
                select a.admission_no, p.name as patient_name, d.name as dept_name,
                       round(extract(epoch from (now() - a.admit_at)) / 3600) as hours
                from inp_admission a
                join empi_patient p on p.id = a.patient_id
                join sys_dept d on d.id = a.dept_id
                where a.admit_at < now() - interval '24 hours'
                  and not exists (select 1 from inp_medical_record r
                                  where r.admission_id = a.id and r.record_type = 'ADMISSION')
                """);
        var missingDischargeSummary = jdbc.queryForList("""
                select a.admission_no, p.name as patient_name
                from inp_admission a join empi_patient p on p.id = a.patient_id
                where a.status = 'DISCHARGED'
                  and not exists (select 1 from inp_medical_record r
                                  where r.admission_id = a.id and r.record_type = 'DISCHARGE')
                """);
        Long unsignedEmr = jdbc.queryForObject(
                "select count(*) from outp_emr where signature is null", Long.class);
        // 首程记录时限（仅在院；FIRST_PROGRESS 为 v34 新记录类型，历史首程混在 PROGRESS 故只报不拦）
        var missingFirstProgress = jdbc.queryForList("""
                select a.admission_no, p.name as patient_name, d.name as dept_name,
                       round(extract(epoch from (now() - a.admit_at)) / 3600) as hours
                from inp_admission a join empi_patient p on p.id = a.patient_id join sys_dept d on d.id = a.dept_id
                where a.status = 'IN_HOSPITAL' and a.admit_at < now() - make_interval(hours => ?)
                  and not exists (select 1 from inp_medical_record r
                                  where r.admission_id = a.id and r.record_type = 'FIRST_PROGRESS')
                """, firstProgressH);
        // 三级查房时限（依赖 ward-round 数据，默认关闭以免历史在院全量误报）
        var missingRound = roundCheck ? jdbc.queryForList("""
                select a.admission_no, p.name as patient_name, d.name as dept_name,
                       round(extract(epoch from (now() - a.admit_at)) / 3600) as hours
                from inp_admission a join empi_patient p on p.id = a.patient_id join sys_dept d on d.id = a.dept_id
                where a.status = 'IN_HOSPITAL' and a.admit_at < now() - make_interval(hours => ?)
                  and not exists (select 1 from inp_medical_record r
                                  where r.admission_id = a.id and r.record_type = 'ROUND')
                """, roundH) : java.util.List.<Map<String, Object>>of();
        // 病程连续性：距上次病程/查房/入院记录超阈值（危重 care_level 特级/一级用更短阈值）
        var progressContinuity = jdbc.queryForList("""
                select a.admission_no, p.name as patient_name, d.name as dept_name, a.care_level,
                       round(extract(epoch from (now() - coalesce(last.ts, a.admit_at))) / 86400.0, 1) as gap_days
                from inp_admission a join empi_patient p on p.id = a.patient_id join sys_dept d on d.id = a.dept_id
                left join lateral (
                    select max(r.created_at) as ts from inp_medical_record r
                    where r.admission_id = a.id and r.record_type in ('PROGRESS','ROUND','ADMISSION','FIRST_PROGRESS')
                ) last on true
                where a.status = 'IN_HOSPITAL'
                  and now() - coalesce(last.ts, a.admit_at) >
                      make_interval(days => case when a.care_level in ('特级','一级') then ? else ? end)
                """, gapCritDays, gapDays);
        // 抢救记录超时闭合：抢救开始超阈值仍未结束/补记完成
        var rescueLate = jdbc.queryForList("""
                select r.id, round(extract(epoch from (now() - r.rescue_start)) / 3600) as hours
                from er_rescue_record r
                where r.rescue_end is null and r.rescue_start < now() - make_interval(hours => ?)
                """, rescueH);

        int defectTotal = missingAdmission.size() + missingDischargeSummary.size() + missingFirstProgress.size()
                + missingRound.size() + progressContinuity.size() + rescueLate.size();
        var breakdown = new java.util.LinkedHashMap<String, Object>();
        breakdown.put("admission", missingAdmission.size());
        breakdown.put("dischargeSummary", missingDischargeSummary.size());
        breakdown.put("firstProgress", missingFirstProgress.size());
        breakdown.put("round", missingRound.size());
        breakdown.put("progressGap", progressContinuity.size());
        breakdown.put("rescue", rescueLate.size());

        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("missingAdmissionRecord", missingAdmission);
        m.put("missingDischargeSummary", missingDischargeSummary);
        m.put("unsignedOutpEmrCount", unsignedEmr);
        m.put("missingFirstProgress", missingFirstProgress);
        m.put("missingRound", missingRound);
        m.put("roundCheckEnabled", roundCheck);
        m.put("progressContinuityDefect", progressContinuity);
        m.put("rescueLateRecord", rescueLate);
        m.put("defectTotal", defectTotal);
        m.put("defectBreakdown", breakdown);
        return R.ok(m);
    }

    /** 病历时限缺陷科室看板（v34）：按科室汇总在院侧各类缺陷计数，供红黄灯预警 */
    @GetMapping("/api/quality/emr-timeliness/board")
    public R<List<Map<String, Object>>> emrTimelinessBoard() {
        int firstProgressH = configReader.getInt("emr.timeliness.first_progress_hours", 8);
        return R.ok(jdbc.queryForList("""
                select d.name as dept_name,
                       count(*) filter (where a.admit_at < now() - interval '24 hours'
                              and not exists (select 1 from inp_medical_record r
                                  where r.admission_id = a.id and r.record_type = 'ADMISSION')) as missing_admission,
                       count(*) filter (where a.admit_at < now() - make_interval(hours => ?)
                              and not exists (select 1 from inp_medical_record r
                                  where r.admission_id = a.id and r.record_type = 'FIRST_PROGRESS')) as missing_first_progress
                from inp_admission a join sys_dept d on d.id = a.dept_id
                where a.status = 'IN_HOSPITAL'
                group by d.name
                having count(*) filter (where a.admit_at < now() - interval '24 hours'
                              and not exists (select 1 from inp_medical_record r
                                  where r.admission_id = a.id and r.record_type = 'ADMISSION')) > 0
                    or count(*) filter (where a.admit_at < now() - make_interval(hours => ?)
                              and not exists (select 1 from inp_medical_record r
                                  where r.admission_id = a.id and r.record_type = 'FIRST_PROGRESS')) > 0
                order by dept_name
                """, firstProgressH, firstProgressH));
    }

    /**
     * 病案首页数据组装（阻塞7）：出院后按住院号从 inp_admission / inp_diagnosis /
     * inp_medical_record / inp_settlement / inp_surgery / empi_patient 自动提取，组装成
     * 结构化病案首页——患者基本信息、入出院信息、诊断（主诊断+其他诊断）、手术、按类费用汇总。
     *
     * <p><b>实现边界（留给实施期）：</b>本实现是院内查看/打印用的病案首页组装，
     * <b>不生成国家标准上报文件</b>（HQMS 住院病案首页报文 / DRG-DIP 结算清单）——
     * 那需对接病案质控配套产品或本地化字典映射（性别/民族/职业/离院方式等国标代码、
     * 手术 ICD-9-CM3 编码、医保结算清单版式），属实施期对接工作，非本平台内置。
     *
     * <p>返回结构中扁平键（total_amount / admit_diag_icd 等）保留向后兼容既有 E2E，
     * 嵌套段（patient / admission / diagnoses / surgeries / fees）供前端病案首页页面渲染。
     */
    @GetMapping("/api/inpatient/admissions/{id}/front-page")
    public R<Map<String, Object>> frontPage(@PathVariable Long id) {
        var rows = jdbc.queryForList("""
                select a.admission_no, a.care_level, a.admit_at, a.discharged_at, a.status, a.archived,
                       a.admit_diag_icd, a.admit_diag_name, a.discharge_diag_icd, a.discharge_diag_name,
                       p.name as patient_name, p.sex, p.birth_date, p.id_no, p.patient_no,
                       p.phone, p.address, p.insurance_type,
                       d.name as dept_name, w.name as ward_name, b.bed_no,
                       s.settle_no, s.total_amount, s.deposit_amount, s.balance, s.pay_method
                from inp_admission a
                join empi_patient p on p.id = a.patient_id
                join sys_dept d on d.id = a.dept_id
                left join sys_dept w on w.id = a.ward_id
                left join inp_bed b on b.id = a.bed_id
                -- 只认 FINAL（v30 中间结算副作用）：一 admission 多张 PAID 会让首页费用段重复
                left join inp_settlement s on s.admission_id = a.id and s.status = 'PAID' and s.settle_type = 'FINAL'
                where a.id = ?
                """, id);
        if (rows.isEmpty()) return R.fail(9802, "住院记录不存在");
        var flat = rows.get(0);

        // 其他诊断（inp_diagnosis 补录的出院/并发症合并症诊断）
        var otherDiags = jdbc.queryForList(
                "select icd, name from inp_diagnosis where admission_id = ? order by id", id);
        // 主诊断：优先取出院主诊断（病案编码口径），未编码则回退入院诊断
        var primary = new java.util.LinkedHashMap<String, Object>();
        Object dxIcd = flat.get("discharge_diag_icd");
        if (dxIcd != null && !String.valueOf(dxIcd).isBlank()) {
            primary.put("icd", dxIcd);
            primary.put("name", flat.get("discharge_diag_name"));
            primary.put("source", "DISCHARGE");
        } else {
            primary.put("icd", flat.get("admit_diag_icd"));
            primary.put("name", flat.get("admit_diag_name"));
            primary.put("source", "ADMIT");
        }
        var diagnoses = new java.util.LinkedHashMap<String, Object>();
        diagnoses.put("primary", primary);
        diagnoses.put("others", otherDiags);

        // 手术信息（若有）
        var surgeries = jdbc.queryForList("""
                select procedure_name, anesthesia_type, scheduled_at, status
                from inp_surgery where admission_id = ? order by coalesce(scheduled_at, created_at), id
                """, id);

        // 费用按类汇总（已执行医嘱行）：DRUG 药品 / LAB 检验 / EXAM 检查 / TREAT 治疗
        var byCategory = jdbc.queryForList("""
                select order_type, coalesce(sum(amount), 0) as amount, count(*) as items
                from inp_order where admission_id = ? and status = 'EXECUTED'
                group by order_type order by order_type
                """, id);
        Double drugAmount = jdbc.queryForObject(
                "select coalesce(sum(amount),0) from inp_order where admission_id = ? and order_type = 'DRUG' and status = 'EXECUTED'",
                Double.class, id);
        Long recordCount = jdbc.queryForObject(
                "select count(*) from inp_medical_record where admission_id = ?", Long.class, id);

        var fees = new java.util.LinkedHashMap<String, Object>();
        fees.put("totalAmount", flat.get("total_amount"));
        fees.put("depositAmount", flat.get("deposit_amount"));
        fees.put("balance", flat.get("balance"));
        fees.put("payMethod", flat.get("pay_method"));
        fees.put("drugAmount", drugAmount);
        fees.put("byCategory", byCategory);

        var page = new java.util.LinkedHashMap<String, Object>();
        // 结构化段（前端病案首页页面渲染）
        page.put("patient", java.util.Map.of(
                "name", nz(flat.get("patient_name")), "sex", nz(flat.get("sex")),
                "birthDate", nz(flat.get("birth_date")), "idNo", nz(flat.get("id_no")),
                "patientNo", nz(flat.get("patient_no")), "phone", nz(flat.get("phone")),
                "address", nz(flat.get("address")), "insuranceType", nz(flat.get("insurance_type"))));
        page.put("admission", java.util.Map.of(
                "admissionNo", nz(flat.get("admission_no")), "deptName", nz(flat.get("dept_name")),
                "wardName", nz(flat.get("ward_name")), "bedNo", nz(flat.get("bed_no")),
                "careLevel", nz(flat.get("care_level")), "admitAt", nz(flat.get("admit_at")),
                "dischargedAt", nz(flat.get("discharged_at")), "status", nz(flat.get("status")),
                "archived", nz(flat.get("archived"))));
        page.put("diagnoses", diagnoses);
        page.put("surgeries", surgeries);
        page.put("fees", fees);
        page.put("recordCount", recordCount);
        // 扁平向后兼容键（既有 E2E 依赖 total_amount / admit_diag_icd）
        page.put("admission_no", flat.get("admission_no"));
        page.put("patient_name", flat.get("patient_name"));
        page.put("admit_diag_icd", flat.get("admit_diag_icd"));
        page.put("admit_diag_name", flat.get("admit_diag_name"));
        page.put("total_amount", flat.get("total_amount"));
        page.put("deposit_amount", flat.get("deposit_amount"));
        page.put("balance", flat.get("balance"));
        page.put("status", flat.get("status"));
        page.put("archived", flat.get("archived"));
        page.put("drugAmount", drugAmount);
        return R.ok(page);
    }

    /** null 安全占位（Map.of 不接受 null 值） */
    private static Object nz(Object v) {
        return v == null ? "" : v;
    }

    /** 病案首页选单：近期出院/在院住院记录（供病案首页页面按住院号选取） */
    @GetMapping("/api/quality/med-records")
    public R<List<Map<String, Object>>> medRecords(@RequestParam(required = false) String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        String like = "%" + kw + "%";
        return R.ok(jdbc.queryForList("""
                select a.id, a.admission_no, a.status, a.discharged_at, a.archived,
                       p.name as patient_name, d.name as dept_name
                from inp_admission a
                join empi_patient p on p.id = a.patient_id
                join sys_dept d on d.id = a.dept_id
                where (? = '' or a.admission_no ilike ? or p.name ilike ?)
                order by coalesce(a.discharged_at, a.admit_at) desc, a.id desc
                limit 100
                """, kw, like, like));
    }

    /**
     * v40 病案室待编码/待归档工作队列：列已出院但尚未收尾的病案（未归档 <b>或</b> 未编码），
     * 出院天数降序（拖最久的排最前），每行带完整性缺项清单，供病案室每天照单干活。
     *
     * <p><b>性能边界：</b>完整性 check 是逐条多次查询（每条约 4–7 趟 DB），故队列硬上限
     * {@value #MR_WORKQUEUE_LIMIT} 条——正常院区待收尾病案是几十条量级，超限说明积压已远超
     * 日常处理能力（返回 truncated=true 提示），此时应先按科室清历史欠账而非在本页翻页。
     *
     * <p>超期口径：出院天数 &gt; sys_config {@code mr.archive.overdue_days}（默认 3）且未归档
     * → overdue=true。纯只读，不 gate 任何写路径。
     */
    @GetMapping("/api/quality/mr-workqueue")
    public R<Map<String, Object>> mrWorkqueue() {
        int overdueDays = configReader.getInt("mr.archive.overdue_days", 3);
        var rows = jdbc.queryForList("""
                select a.id, a.admission_no, a.discharged_at, a.archived,
                       a.discharge_diag_icd, a.discharge_diag_name,
                       p.name as patient_name, d.name as dept_name,
                       floor(extract(epoch from (now() - a.discharged_at)) / 86400)::int as discharged_days
                from inp_admission a
                join empi_patient p on p.id = a.patient_id
                join sys_dept d on d.id = a.dept_id
                where a.status = 'DISCHARGED'
                  and (a.archived = false or a.discharge_diag_icd is null)
                order by a.discharged_at asc nulls first, a.id asc
                limit ?
                """, MR_WORKQUEUE_LIMIT + 1);
        boolean truncated = rows.size() > MR_WORKQUEUE_LIMIT;
        if (truncated) rows = rows.subList(0, MR_WORKQUEUE_LIMIT);

        var items = new java.util.ArrayList<Map<String, Object>>(rows.size());
        for (var r : rows) {
            Long admId = ((Number) r.get("id")).longValue();
            Object dxIcd = r.get("discharge_diag_icd");
            boolean coded = dxIcd != null && !String.valueOf(dxIcd).isBlank();
            boolean archived = Boolean.TRUE.equals(r.get("archived"));
            Integer days = (Integer) r.get("discharged_days");
            var missing = emrIntegrityService.check(admId);

            var item = new java.util.LinkedHashMap<String, Object>();
            item.put("id", admId);
            item.put("admissionNo", r.get("admission_no"));
            item.put("patientName", r.get("patient_name"));
            item.put("deptName", r.get("dept_name"));
            item.put("dischargedAt", r.get("discharged_at"));
            item.put("dischargedDays", days == null ? 0 : days);
            item.put("coded", coded);
            item.put("dischargeDiagIcd", dxIcd);
            item.put("dischargeDiagName", r.get("discharge_diag_name"));
            item.put("archived", archived);
            item.put("missing", missing);
            item.put("missingCount", missing.size());
            item.put("overdue", !archived && days != null && days > overdueDays);
            items.add(item);
        }
        // 出院天数降序 = discharged_at 升序（SQL 已排；null 出院时间排最前当作最久）
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("items", items);
        m.put("total", items.size());
        m.put("overdueDays", overdueDays);
        m.put("pendingCode", items.stream().filter(i -> !Boolean.TRUE.equals(i.get("coded"))).count());
        m.put("pendingArchive", items.stream().filter(i -> !Boolean.TRUE.equals(i.get("archived"))).count());
        m.put("overdueCount", items.stream().filter(i -> Boolean.TRUE.equals(i.get("overdue"))).count());
        m.put("limit", MR_WORKQUEUE_LIMIT);
        m.put("truncated", truncated);
        return R.ok(m);
    }

    /** 工作队列上限：逐条完整性 check 有 N+1 代价，超此量级说明积压需按科室专项清理 */
    private static final int MR_WORKQUEUE_LIMIT = 200;

    /** 病案归档（须已出院） */
    @PutMapping("/api/inpatient/admissions/{id}/archive")
    public R<Object> archive(@PathVariable Long id) {
        // v35 归档病历完整性 gate（试点期可配 emr.gate.archive，默认 warn 警告放行）
        String mode = configReader.get("emr.gate.archive", "warn");
        var missing = "off".equals(mode) ? java.util.List.<String>of() : emrIntegrityService.check(id);
        if (!missing.isEmpty() && "block".equals(mode)) {
            return R.fail(9820, "病历不完整，不能归档：" + String.join("、", missing));
        }
        int n = jdbc.update(
                "update inp_admission set archived = true where id = ? and status = 'DISCHARGED'", id);
        if (n == 0) return R.fail(9803, "仅出院病历可归档");
        return missing.isEmpty() ? R.ok() : R.ok(Map.of("warning", "病历不完整已放行：" + String.join("、", missing)));
    }

    public record AdverseEventReq(String type, Integer level, String occurredOn, Long deptId,
                                  String description, Boolean anonymous) {}

    @PostMapping("/api/quality/adverse-events")
    public R<Void> reportAdverse(@RequestBody AdverseEventReq req, Authentication auth) {
        if (req.level() == null || req.level() < 1 || req.level() > 4) return R.fail(9804, "事件分级须为 1-4");
        boolean anon = Boolean.TRUE.equals(req.anonymous());
        jdbc.update("""
                insert into qc_adverse_event(type, level, occurred_on, dept_id, description, anonymous, reporter_id)
                values (?,?,?::date,?,?,?,?)
                """, req.type(), req.level(), req.occurredOn(), req.deptId(), req.description(), anon,
                anon ? null : currentUserService.idOf(auth));
        return R.ok();
    }

    @GetMapping("/api/quality/adverse-events")
    public R<List<Map<String, Object>>> adverseEvents() {
        return R.ok(jdbc.queryForList("""
                select e.id, e.type, e.level, e.occurred_on, e.description, e.anonymous, e.status, e.handle_note,
                       d.name as dept_name
                from qc_adverse_event e left join sys_dept d on d.id = e.dept_id
                order by case e.status when 'NEW' then 0 else 1 end, e.level, e.id desc limit 100
                """));
    }

    @PutMapping("/api/quality/adverse-events/{id}/handle")
    public R<Void> handleAdverse(@PathVariable Long id, @RequestParam String note, Authentication auth) {
        int n = jdbc.update("""
                update qc_adverse_event set status = 'HANDLED', handle_note = ?, handler_id = ?
                where id = ? and status = 'NEW'
                """, note, currentUserService.idOf(auth), id);
        return n == 0 ? R.fail(9805, "事件不存在或已处理") : R.ok();
    }

    public record InfectionReq(Long admissionId, String site, String pathogen, String confirmedOn, String note) {}

    @PostMapping("/api/quality/infections")
    public R<Void> reportInfection(@RequestBody InfectionReq req) {
        jdbc.update("insert into qc_infection_case(admission_id, site, pathogen, confirmed_on, note) values (?,?,?,?::date,?)",
                req.admissionId(), req.site(), req.pathogen(), req.confirmedOn(), req.note());
        return R.ok();
    }

    /** 院感统计：登记病例 + 抗菌药物使用监测 */
    @GetMapping("/api/quality/infections")
    public R<Map<String, Object>> infections() {
        return R.ok(Map.of(
                "cases", jdbc.queryForList("""
                        select c.id, c.site, c.pathogen, c.confirmed_on, c.note,
                               a.admission_no, p.name as patient_name
                        from qc_infection_case c
                        join inp_admission a on a.id = c.admission_id
                        join empi_patient p on p.id = a.patient_id
                        order by c.id desc limit 100
                        """),
                "antibioticOrderCount", jdbc.queryForObject("""
                        select count(*) from inp_order o join md_drug d on d.id = o.item_id
                        where o.order_type = 'DRUG' and d.antibiotic and o.status = 'EXECUTED'
                        """, Long.class)));
    }
}
