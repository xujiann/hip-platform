package cn.hip.qualitycare.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.inpatient.service.EmrIntegrityService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v42 车道3：病案<b>终末</b>质控评分与甲乙丙评级。
 *
 * <p>与既有「病案质控」的分工（此前全仓只有前者，本控制器补的是后者）：
 * <ul>
 *   <li><b>环节质控</b>（{@code /api/quality/emr-timeliness}、{@code /api/quality/mr-workqueue}）：
 *       在院实时现算、不落库、无历史，看的是「现在还差什么」；
 *   <li><b>终末质控</b>（本控制器）：出院后一次性评分定级、<b>落库可回溯</b>，
 *       看的是「这份病案最终值几分、甲乙丙」。
 * </ul>
 *
 * <p><b>甲乙丙不是 gate。</b>评级是事后管理评价，丙级病案照样归档、照样结算——本控制器
 * 不碰 {@code emr.gate.discharge} / {@code emr.gate.archive}，也不写任何既有业务表。
 *
 * <p><b>「自动评审与打分」的兑现口径（诚实标注）</b>：自动预填 = 把
 * {@link EmrIntegrityService#checkDetailed(Long)} 判出的结构化缺项，按
 * {@code mr_qc_item.auto_rule} 映射成 AUTO 来源的扣分明细。<b>不是</b> NLP 病历内涵语义评审——
 * 内涵项（诊断选择是否正确、病程是否体现诊疗思维等）一律是人工评审项，机器判不了，也不假装能判。
 *
 * <p><b>外部边界</b>：只做院内自定义评分表。<b>不生成</b>病案首页国标上报报文（HQMS）、
 * <b>不生成</b> DRG-DIP 医保结算清单、不预置任何国标码值。
 *
 * <p>错误码段 4840–4859（见 docs/错误码分段.md）：4840 评分单不存在、4841 已提交不可再评、
 * 4842 扣分项不存在或已停用、4843 扣分分值越界、4844 病案未出院不可终末评分、
 * 4845 评级阈值配置非法、4846 扣分项编码重复。
 */
@RestController
@RequestMapping("/api/quality/mr-qc")
@PreAuthorize("hasAnyRole('ADMIN','QUALITY')")
@RequiredArgsConstructor
public class MrQcController {

    private final JdbcTemplate jdbc;
    private final ConfigReader configReader;
    private final CurrentUserService currentUserService;
    private final EmrIntegrityService emrIntegrityService;

    /**
     * 待评队列上限。本队列<b>不</b>逐条跑完整性 check（那是 mr-workqueue 的 N+1 代价所在），
     * 纯一趟 SQL；上限只为防「历史欠账几万条一次性拉回前端」。
     */
    private static final int PENDING_LIMIT = 200;

    /** 扣分分值合法域：(0, 100]，与 mr_qc_item / mr_qc_sheet_item 的 CHECK 约束同口径 */
    private static final BigDecimal MIN_DEDUCT = new BigDecimal("0.00");
    private static final BigDecimal MAX_DEDUCT = new BigDecimal("100.00");

    /**
     * 口径诚实标注（端点 javadoc 与页面 alert 必须同步）：{@code inp_admission.archived_at}
     * 由 V130 才添加，<b>V130 之前归档的病案该列恒为 null</b>——严禁用 discharged_at 回填
     * （出院≠归档，回填等于伪造归档痕迹）。任何按归档时间的统计都只覆盖 V130 之后的归档行。
     */
    private static final String ARCHIVED_AT_CAVEAT =
            "归档时间（archived_at）自 v42 起才落库，此前已归档的病案该列为空，"
            + "按归档时间的口径只覆盖 v42 之后的归档记录（不以出院时间回填）";

    /** 统计月份维度取 reviewed_at（质控月），不是出院月——同一批病案可能跨月补评 */
    private static final String MONTH_CAVEAT = "月份维度取质控提交时间（reviewed_at），非出院月份；"
            + "仅统计已提交（SUBMITTED）的评分单，草稿不计入";

    // ==================================================================================
    // 一、扣分项字典 CRUD
    // ==================================================================================

    public record ItemReq(String code, String category, String name, BigDecimal deductScore,
                          String autoRule, Boolean enabled, Integer sortNo) {}

    /** 扣分项字典查询（category / enabled 可选过滤；默认只列启用项，评分单加项只能用启用项） */
    @GetMapping("/items")
    public R<List<Map<String, Object>>> items(@RequestParam(required = false) String category,
                                              @RequestParam(required = false) Boolean enabled) {
        var sql = new StringBuilder("""
                select id, code, category, name, deduct_score, auto_rule, enabled, sort_no
                from mr_qc_item where 1 = 1
                """);
        var args = new ArrayList<Object>();
        if (category != null && !category.isBlank()) {
            sql.append(" and category = ?");
            args.add(category.strip());
        }
        if (enabled != null) {
            sql.append(" and enabled = ?");
            args.add(enabled);
        }
        sql.append(" order by category, sort_no, code");
        return R.ok(jdbc.queryForList(sql.toString(), args.toArray()));
    }

    @PostMapping("/items")
    public R<Map<String, Object>> createItem(@RequestBody ItemReq req) {
        if (req.code() == null || req.code().isBlank() || req.name() == null || req.name().isBlank()
                || req.category() == null || req.category().isBlank()) {
            return R.fail(4842, "扣分项编码、一级项与名称必填");
        }
        if (req.deductScore() == null) return R.fail(4843, "扣分分值必填，须在 0（不含）到 100（含）之间");
        var bad = validateDeduct(req.deductScore());
        if (bad != null) return bad;
        String code = req.code().strip();
        Integer dup = jdbc.queryForObject("select count(*) from mr_qc_item where code = ?", Integer.class, code);
        if (dup != null && dup > 0) return R.fail(4846, "扣分项编码已存在：" + code);
        jdbc.update("""
                insert into mr_qc_item (code, category, name, deduct_score, auto_rule, enabled, sort_no)
                values (?, ?, ?, ?, ?, ?, ?)
                """, code, req.category().strip(), req.name().strip(), req.deductScore(),
                blankToNull(req.autoRule()), req.enabled() == null || req.enabled(),
                req.sortNo() == null ? 0 : req.sortNo());
        return R.ok(one("select id, code, category, name, deduct_score, auto_rule, enabled, sort_no "
                + "from mr_qc_item where code = ?", code));
    }

    @PutMapping("/items/{id}")
    public R<Map<String, Object>> updateItem(@PathVariable Long id, @RequestBody ItemReq req) {
        var cur = one("select id, code, enabled from mr_qc_item where id = ?", id);
        if (cur == null) return R.fail(4842, "扣分项不存在");
        var bad = validateDeduct(req.deductScore());
        if (bad != null) return bad;
        jdbc.update("""
                update mr_qc_item set category = coalesce(?, category), name = coalesce(?, name),
                       deduct_score = coalesce(?, deduct_score), auto_rule = ?,
                       enabled = coalesce(?, enabled), sort_no = coalesce(?, sort_no)
                where id = ?
                """, blankToNull(req.category()), blankToNull(req.name()), req.deductScore(),
                blankToNull(req.autoRule()), req.enabled(), req.sortNo(), id);
        return R.ok(one("select id, code, category, name, deduct_score, auto_rule, enabled, sort_no "
                + "from mr_qc_item where id = ?", id));
    }

    /**
     * 停用扣分项（软删除，不 delete）：历史评分单的明细行按 item_code 冗余存了名称与分值，
     * 物理删字典会让「当时为什么扣这 5 分」永久失去解释——评分单是考核依据，回溯期以年计。
     */
    @DeleteMapping("/items/{id}")
    public R<Void> disableItem(@PathVariable Long id) {
        int n = jdbc.update("update mr_qc_item set enabled = false where id = ?", id);
        return n == 0 ? R.fail(4842, "扣分项不存在") : R.ok();
    }

    private R<Map<String, Object>> validateDeduct(BigDecimal score) {
        if (score == null) return null;   // 更新时不传 = 不改
        if (score.compareTo(MIN_DEDUCT) <= 0 || score.compareTo(MAX_DEDUCT) > 0) {
            return R.fail(4843, "扣分分值须在 0（不含）到 100（含）之间，当前 " + score.toPlainString());
        }
        return null;
    }

    // ==================================================================================
    // 二、评分单：待评队列 / 自动预填 / 人工增删 / 提交定级
    // ==================================================================================

    /**
     * 待终末质控队列：已出院、评分单不存在或仍为草稿的病案，出院最久的排最前。
     *
     * <p>本队列<b>不</b>逐条跑完整性检查（那是 {@code /api/quality/mr-workqueue} 的 N+1 代价），
     * 一趟 SQL 取回，上限 {@value #PENDING_LIMIT} 条。缺项要看逐份点开预填。
     *
     * <p><b>口径</b>：{@code archivedAt} 可能为 null——{@value #ARCHIVED_AT_CAVEAT}。
     */
    @GetMapping("/sheets/pending")
    public R<Map<String, Object>> pending(@RequestParam(required = false) Long deptId) {
        var args = new ArrayList<Object>();
        var where = new StringBuilder();
        if (deptId != null) {
            where.append(" and a.dept_id = ?");
            args.add(deptId);
        }
        args.add(PENDING_LIMIT + 1);
        var rows = jdbc.queryForList("""
                select a.id as admission_id, a.admission_no, p.name as patient_name, d.name as dept_name,
                       a.discharged_at, a.archived, a.archived_at,
                       floor(extract(epoch from (now() - a.discharged_at)) / 86400)::int as discharged_days,
                       s.id as sheet_id, s.status as sheet_status, s.final_score, s.grade
                from inp_admission a
                join empi_patient p on p.id = a.patient_id
                join sys_dept d on d.id = a.dept_id
                left join mr_qc_sheet s on s.admission_id = a.id
                where a.status = 'DISCHARGED' and (s.id is null or s.status = 'DRAFT')
                """ + where + """
                order by a.discharged_at asc nulls first, a.id asc
                limit ?
                """, args.toArray());
        boolean truncated = rows.size() > PENDING_LIMIT;
        if (truncated) rows = rows.subList(0, PENDING_LIMIT);

        var m = new LinkedHashMap<String, Object>();
        m.put("items", rows);
        m.put("total", rows.size());
        m.put("limit", PENDING_LIMIT);
        m.put("truncated", truncated);
        m.put("archivedAtCaveat", ARCHIVED_AT_CAVEAT);
        return R.ok(m);
    }

    /** 评分单详情（含扣分明细）。不存在返回 4840——列表页应先 prefill 建单。 */
    @GetMapping("/sheets/{admissionId}")
    public R<Map<String, Object>> sheet(@PathVariable Long admissionId) {
        var sheet = loadSheet(admissionId);
        if (sheet == null) return R.fail(4840, "该病案尚无终末质控评分单，请先自动预填建单");
        return R.ok(sheet);
    }

    /**
     * 自动预填（兑现偏离表 2734「按标准自动评审与打分」的院内口径）：把
     * {@link EmrIntegrityService#checkDetailed(Long)} 的结构化缺项按
     * {@code mr_qc_item.auto_rule} 映射为 AUTO 来源扣分明细，重建时只清 AUTO 行，人工加项保留。
     *
     * <p><b>设计约束：只按单份 admission 触发，不提供「一键全院预填」。</b>
     * 逐份完整性检查是 N+1（每份 4–7 趟 DB，mr-workqueue 已因此硬限 200 条），
     * 全院预填在有历史欠账的院区会直接把库打爆——预填必须由病案室逐份点开触发。
     *
     * <p>未出院 → 4844（终末质控的前提是病案已终结）；已提交 → 4841。
     */
    @PostMapping("/sheets/{admissionId}/prefill")
    public R<Map<String, Object>> prefill(@PathVariable Long admissionId) {
        var adm = one("select id, status from inp_admission where id = ?", admissionId);
        if (adm == null) return R.fail(4844, "住院记录不存在");
        if (!"DISCHARGED".equals(String.valueOf(adm.get("status")))) {
            return R.fail(4844, "病案未出院，不可做终末质控评分（终末质控的前提是病案已终结）");
        }
        var existing = one("select id, status from mr_qc_sheet where admission_id = ?", admissionId);
        if (existing != null && "SUBMITTED".equals(String.valueOf(existing.get("status")))) {
            return R.fail(4841, "评分单已提交，不可再评");
        }
        Long sheetId;
        if (existing == null) {
            jdbc.update("insert into mr_qc_sheet (admission_id) values (?) on conflict (admission_id) do nothing",
                    admissionId);
            sheetId = jdbc.queryForObject("select id from mr_qc_sheet where admission_id = ?", Long.class, admissionId);
        } else {
            sheetId = ((Number) existing.get("id")).longValue();
        }

        // 重建 AUTO 行：人工加的 MANUAL 行不动（质控员的判断不该被一次刷新抹掉）
        jdbc.update("delete from mr_qc_sheet_item where sheet_id = ? and source = 'AUTO'", sheetId);
        int filled = 0;
        var unmapped = new ArrayList<String>();
        for (var f : emrIntegrityService.checkDetailed(admissionId)) {
            var dict = one("""
                    select code, deduct_score from mr_qc_item
                    where auto_rule = ? and enabled = true order by sort_no, code limit 1
                    """, f.code());
            if (dict == null) {
                // 字典里没配（或已停用）对应规则的扣分项：如实回报，不静默丢弃也不凭空造分
                unmapped.add(f.text());
                continue;
            }
            filled += jdbc.update("""
                    insert into mr_qc_sheet_item (sheet_id, item_code, deduct_score, source, remark)
                    values (?, ?, ?, 'AUTO', ?)
                    on conflict (sheet_id, item_code) do update
                       set deduct_score = excluded.deduct_score, source = 'AUTO', remark = excluded.remark
                    """, sheetId, dict.get("code"), dict.get("deduct_score"), "系统自动判定：" + f.text());
        }
        recompute(sheetId);
        var result = loadSheet(admissionId);
        if (result != null) {
            result.put("autoFilled", filled);
            result.put("unmappedFindings", unmapped);
        }
        return R.ok(result);
    }

    public record SheetItemReq(String itemCode, BigDecimal deductScore, String remark) {}

    /** 人工加扣分项（内涵评审项走这里）。分值不传取字典标准分；同项重复加视为覆盖。 */
    @PostMapping("/sheets/{admissionId}/items")
    public R<Map<String, Object>> addItem(@PathVariable Long admissionId, @RequestBody SheetItemReq req) {
        var sheet = one("select id, status from mr_qc_sheet where admission_id = ?", admissionId);
        if (sheet == null) return R.fail(4840, "该病案尚无终末质控评分单，请先自动预填建单");
        if ("SUBMITTED".equals(String.valueOf(sheet.get("status")))) return R.fail(4841, "评分单已提交，不可再评");
        if (req.itemCode() == null || req.itemCode().isBlank()) return R.fail(4842, "扣分项编码必填");

        var dict = one("select code, deduct_score from mr_qc_item where code = ? and enabled = true",
                req.itemCode().strip());
        if (dict == null) return R.fail(4842, "扣分项不存在或已停用：" + req.itemCode());
        BigDecimal deduct = req.deductScore() != null
                ? req.deductScore() : (BigDecimal) dict.get("deduct_score");
        var bad = validateDeduct(deduct);
        if (bad != null) return bad;

        Long sheetId = ((Number) sheet.get("id")).longValue();
        jdbc.update("""
                insert into mr_qc_sheet_item (sheet_id, item_code, deduct_score, source, remark)
                values (?, ?, ?, 'MANUAL', ?)
                on conflict (sheet_id, item_code) do update
                   set deduct_score = excluded.deduct_score, source = 'MANUAL', remark = excluded.remark
                """, sheetId, dict.get("code"), deduct, blankToNull(req.remark()));
        recompute(sheetId);
        return R.ok(loadSheet(admissionId));
    }

    /** 删除扣分明细（AUTO 行也可删——自动判定误报时质控员有最终裁量权） */
    @DeleteMapping("/sheets/{admissionId}/items/{itemCode}")
    public R<Map<String, Object>> removeItem(@PathVariable Long admissionId, @PathVariable String itemCode) {
        var sheet = one("select id, status from mr_qc_sheet where admission_id = ?", admissionId);
        if (sheet == null) return R.fail(4840, "该病案尚无终末质控评分单");
        if ("SUBMITTED".equals(String.valueOf(sheet.get("status")))) return R.fail(4841, "评分单已提交，不可再评");
        Long sheetId = ((Number) sheet.get("id")).longValue();
        jdbc.update("delete from mr_qc_sheet_item where sheet_id = ? and item_code = ?", sheetId, itemCode);
        recompute(sheetId);
        return R.ok(loadSheet(admissionId));
    }

    public record SubmitReq(String note) {}

    /**
     * 提交评分并定级：final_score = max(base_score - 扣分合计, 0)，按 sys_config
     * {@code mr.qc.grade_a_min}（默认 90）/ {@code mr.qc.grade_b_min}（默认 80）判甲/乙/丙。
     *
     * <p>阈值配置非法（非数字、越界、b_min >= a_min）→ 4845 而非静默回落默认值：
     * 评级是考核依据，「配置写错了」不能悄悄变成「按另一套标准打分」。
     *
     * <p><b>提交不 gate 任何写路径</b>：丙级病案照样归档、照样结算。
     */
    @PostMapping("/sheets/{admissionId}/submit")
    public R<Map<String, Object>> submit(@PathVariable Long admissionId,
                                         @RequestBody(required = false) SubmitReq req,
                                         Authentication authentication) {
        var sheet = one("select s.id, s.status, a.status as adm_status from mr_qc_sheet s "
                + "join inp_admission a on a.id = s.admission_id where s.admission_id = ?", admissionId);
        if (sheet == null) return R.fail(4840, "该病案尚无终末质控评分单，请先自动预填建单");
        if ("SUBMITTED".equals(String.valueOf(sheet.get("status")))) return R.fail(4841, "评分单已提交，不可再评");
        if (!"DISCHARGED".equals(String.valueOf(sheet.get("adm_status")))) {
            return R.fail(4844, "病案未出院，不可做终末质控评分（终末质控的前提是病案已终结）");
        }
        int[] th = thresholds();
        if (th == null) {
            return R.fail(4845, "评级阈值配置非法：mr.qc.grade_a_min / mr.qc.grade_b_min 须为 "
                    + "0 <= 乙级线 < 甲级线 <= 100 的整数");
        }
        Long sheetId = ((Number) sheet.get("id")).longValue();
        BigDecimal finalScore = recompute(sheetId);
        String grade = grade(finalScore, th[0], th[1]);
        jdbc.update("""
                update mr_qc_sheet set status = 'SUBMITTED', grade = ?, reviewer_id = ?,
                       reviewed_at = now(), note = coalesce(?, note)
                where id = ? and status = 'DRAFT'
                """, grade, currentUserService.idOf(authentication),
                req == null ? null : blankToNull(req.note()), sheetId);
        return R.ok(loadSheet(admissionId));
    }

    /**
     * 单份病案质控摘要（兑现偏离表序号 7「病案首页质控：展示当前病例的首页质控汇总结果及扣分项明细」）。
     *
     * <p>供病案首页页面调用，<b>未评分不报错</b>（返回 {@code scored=false}）——病案首页对所有
     * 在院/出院病案都要能打开，为「还没质控」弹一条红字是噪音。未评分时附带
     * {@code autoFindings} 自动判定的缺项预览，让首页也能看到「预计会扣哪些项」。
     *
     * <p>只读，放宽到 NURSE/DOCTOR：病案首页本身就是这几类角色在看。
     */
    @GetMapping("/summary/{admissionId}")
    @PreAuthorize("hasAnyRole('ADMIN','QUALITY','NURSE','DOCTOR')")
    public R<Map<String, Object>> summary(@PathVariable Long admissionId) {
        var sheet = loadSheet(admissionId);
        if (sheet != null) {
            sheet.put("scored", true);
            return R.ok(sheet);
        }
        var m = new LinkedHashMap<String, Object>();
        m.put("scored", false);
        m.put("admissionId", admissionId);
        m.put("items", List.of());
        var findings = new ArrayList<Map<String, Object>>();
        for (var f : emrIntegrityService.checkDetailed(admissionId)) {
            findings.add(Map.of("code", f.code(), "text", f.text()));
        }
        m.put("autoFindings", findings);
        m.put("note", "该病案尚未做终末质控评分；上列为系统自动判定的完整性缺项预览，"
                + "非最终评分结果（内涵评审项需人工评定）");
        return R.ok(m);
    }

    // ==================================================================================
    // 三、统计（兑现偏离表 2647 分类汇总/科室维度/趋势 与 序号 22 TOP10/扣分原因/导出）
    // ==================================================================================

    /**
     * 终末质控统计：按月×科室的甲/乙/丙份数与甲级率、扣分项 TOP10（扣分原因）、
     * 科室排名、评分 TOP10 病案。支持按科室与评级自定义过滤（序号 22「自定义条件查询」）。
     *
     * <p><b>口径（页面 alert 同步展示）</b>：
     * <ul>
     *   <li>{@value #MONTH_CAVEAT}；
     *   <li>「运行质控 / 终末质控」两类评分的分类汇总中，<b>本平台只落库终末质控评分单</b>——
     *       运行（环节）质控是在院实时现算、不落库，故其列在返回体中恒为 0 并标注
     *       {@code runningQcPersisted=false}，<b>不用现算值冒充历史评分</b>；
     *   <li>{@value #ARCHIVED_AT_CAVEAT}。
     * </ul>
     */
    @GetMapping("/stats")
    public R<Map<String, Object>> stats(@RequestParam(defaultValue = "12") int months,
                                        @RequestParam(required = false) Long deptId,
                                        @RequestParam(required = false) String grade) {
        int m = Math.min(Math.max(months, 1), 36);
        var res = new LinkedHashMap<String, Object>();
        res.put("months", m);
        res.put("byMonthDept", jdbc.queryForList(BY_MONTH_DEPT_SQL + filterSql(deptId, grade)
                + " group by 1, 2 order by 1 desc, 2", filterArgs(m, deptId, grade)));
        res.put("deptRank", jdbc.queryForList(DEPT_RANK_SQL + filterSql(deptId, grade)
                + " group by d.name order by avg_score desc nulls last, sheets desc",
                filterArgs(m, deptId, grade)));
        res.put("topDeductItems", jdbc.queryForList(TOP_DEDUCT_SQL + filterSql(deptId, grade)
                + " group by i.item_code, t.name, t.category order by times desc, total_deduct desc limit 10",
                filterArgs(m, deptId, grade)));
        res.put("topSheets", jdbc.queryForList(TOP_SHEETS_SQL + filterSql(deptId, grade)
                + " order by s.final_score desc, s.reviewed_at desc limit 10", filterArgs(m, deptId, grade)));
        res.put("totals", one(TOTALS_SQL + filterSql(deptId, grade), filterArgs(m, deptId, grade)));
        res.put("byScoreType", byScoreType(m, deptId, grade));
        res.put("runningQcPersisted", false);
        res.put("monthCaveat", MONTH_CAVEAT);
        res.put("archivedAtCaveat", ARCHIVED_AT_CAVEAT);
        return R.ok(res);
    }

    /**
     * 「按评分类型分类汇总」（偏离表 2647）：运行质控 / 终末质控两类。
     *
     * <p><b>诚实口径</b>：运行（环节）质控在本平台是实时现算、<b>不落库</b>的只读看板
     * （{@code /api/quality/emr-timeliness}），没有历史评分单可汇总，故份数恒 0、
     * {@code persisted=false}。用现算值冒充历史评分会让趋势线完全失真。
     */
    private List<Map<String, Object>> byScoreType(int months, Long deptId, String grade) {
        var terminal = one(TOTALS_SQL + filterSql(deptId, grade), filterArgs(months, deptId, grade));
        var rows = new ArrayList<Map<String, Object>>(2);
        var running = new LinkedHashMap<String, Object>();
        running.put("scoreType", "RUNNING");
        running.put("scoreTypeName", "运行质控（环节）");
        running.put("sheets", 0);
        running.put("avgScore", null);
        running.put("persisted", false);
        running.put("note", "环节质控为在院实时现算的只读看板，本平台不落库评分单，无历史可汇总");
        rows.add(running);
        var t = new LinkedHashMap<String, Object>();
        t.put("scoreType", "TERMINAL");
        t.put("scoreTypeName", "终末质控");
        t.put("sheets", terminal == null ? 0 : terminal.get("sheets"));
        t.put("avgScore", terminal == null ? null : terminal.get("avg_score"));
        t.put("persisted", true);
        rows.add(t);
        return rows;
    }

    /** 统计 CSV 导出（Excel 可直接打开；序号 22「导出 Excel」的兑现口径）——与 /stats 同 SQL 同口径 */
    @GetMapping(value = "/stats.csv", produces = "text/csv;charset=UTF-8")
    public String statsCsv(@RequestParam(defaultValue = "12") int months,
                           @RequestParam(required = false) Long deptId,
                           @RequestParam(required = false) String grade) {
        int m = Math.min(Math.max(months, 1), 36);
        var rows = jdbc.queryForList(BY_MONTH_DEPT_SQL + filterSql(deptId, grade)
                + " group by 1, 2 order by 1 desc, 2", filterArgs(m, deptId, grade));
        var sb = new StringBuilder("﻿月份,科室,评分份数,甲级,乙级,丙级,甲级率(%),平均分\n");
        for (var r : rows) {
            sb.append("%s,%s,%s,%s,%s,%s,%s,%s\n".formatted(
                    csv(r.get("month")), csv(r.get("dept_name")), csv(r.get("sheets")),
                    csv(r.get("grade_a")), csv(r.get("grade_b")), csv(r.get("grade_c")),
                    csv(r.get("grade_a_rate")), csv(r.get("avg_score"))));
        }
        sb.append('\n').append(csv("口径：" + MONTH_CAVEAT)).append('\n');
        sb.append(csv("口径：" + ARCHIVED_AT_CAVEAT)).append('\n');
        return sb.toString();
    }

    /** 扣分项 TOP10 明细 CSV（扣分情况与扣分原因，序号 22） */
    @GetMapping(value = "/deduct-items.csv", produces = "text/csv;charset=UTF-8")
    public String deductItemsCsv(@RequestParam(defaultValue = "12") int months,
                                 @RequestParam(required = false) Long deptId,
                                 @RequestParam(required = false) String grade) {
        int m = Math.min(Math.max(months, 1), 36);
        var rows = jdbc.queryForList(TOP_DEDUCT_SQL + filterSql(deptId, grade)
                + " group by i.item_code, t.name, t.category order by times desc, total_deduct desc limit 10",
                filterArgs(m, deptId, grade));
        var sb = new StringBuilder("﻿扣分项编码,一级项,扣分项,发生次数,扣分合计\n");
        for (var r : rows) {
            sb.append("%s,%s,%s,%s,%s\n".formatted(
                    csv(r.get("item_code")), csv(r.get("category")), csv(r.get("item_name")),
                    csv(r.get("times")), csv(r.get("total_deduct"))));
        }
        return sb.toString();
    }

    // ---- 统计 SQL（各段共用同一 where 前缀，过滤条件由 filterSql/filterArgs 追加）----

    private static final String SUBMITTED_WINDOW = """
            where s.status = 'SUBMITTED' and s.reviewed_at is not null
              and s.reviewed_at >= date_trunc('month', now()) - make_interval(months => ?)
            """;

    private static final String BY_MONTH_DEPT_SQL = """
            select to_char(s.reviewed_at, 'YYYY-MM') as month, d.name as dept_name,
                   count(*) as sheets,
                   count(*) filter (where s.grade = '甲') as grade_a,
                   count(*) filter (where s.grade = '乙') as grade_b,
                   count(*) filter (where s.grade = '丙') as grade_c,
                   round(100.0 * count(*) filter (where s.grade = '甲') / count(*), 1) as grade_a_rate,
                   round(avg(s.final_score), 1) as avg_score
            from mr_qc_sheet s
            join inp_admission a on a.id = s.admission_id
            join sys_dept d on d.id = a.dept_id
            """ + SUBMITTED_WINDOW;

    private static final String DEPT_RANK_SQL = """
            select d.name as dept_name, count(*) as sheets,
                   round(avg(s.final_score), 1) as avg_score,
                   count(*) filter (where s.grade = '甲') as grade_a,
                   count(*) filter (where s.grade = '丙') as grade_c,
                   round(100.0 * count(*) filter (where s.grade = '甲') / count(*), 1) as grade_a_rate
            from mr_qc_sheet s
            join inp_admission a on a.id = s.admission_id
            join sys_dept d on d.id = a.dept_id
            """ + SUBMITTED_WINDOW;

    private static final String TOP_DEDUCT_SQL = """
            select i.item_code,
                   coalesce(t.name, i.item_code) as item_name,
                   coalesce(t.category, '（字典已删）') as category,
                   count(*) as times,
                   round(sum(i.deduct_score), 1) as total_deduct
            from mr_qc_sheet_item i
            join mr_qc_sheet s on s.id = i.sheet_id
            join inp_admission a on a.id = s.admission_id
            left join mr_qc_item t on t.code = i.item_code
            """ + SUBMITTED_WINDOW;

    private static final String TOP_SHEETS_SQL = """
            select a.id as admission_id, a.admission_no, p.name as patient_name, d.name as dept_name,
                   s.final_score, s.grade, s.total_deduct, s.reviewed_at
            from mr_qc_sheet s
            join inp_admission a on a.id = s.admission_id
            join empi_patient p on p.id = a.patient_id
            join sys_dept d on d.id = a.dept_id
            """ + SUBMITTED_WINDOW;

    private static final String TOTALS_SQL = """
            select count(*) as sheets,
                   count(*) filter (where s.grade = '甲') as grade_a,
                   count(*) filter (where s.grade = '乙') as grade_b,
                   count(*) filter (where s.grade = '丙') as grade_c,
                   round(100.0 * count(*) filter (where s.grade = '甲') / nullif(count(*), 0), 1) as grade_a_rate,
                   round(avg(s.final_score), 1) as avg_score
            from mr_qc_sheet s
            join inp_admission a on a.id = s.admission_id
            """ + SUBMITTED_WINDOW;

    private static String filterSql(Long deptId, String grade) {
        var sb = new StringBuilder();
        if (deptId != null) sb.append(" and a.dept_id = ?");
        if (grade != null && !grade.isBlank()) sb.append(" and s.grade = ?");
        return sb.toString();
    }

    private static Object[] filterArgs(int months, Long deptId, String grade) {
        var args = new ArrayList<Object>();
        args.add(months);
        if (deptId != null) args.add(deptId);
        if (grade != null && !grade.isBlank()) args.add(grade.strip());
        return args.toArray();
    }

    // ==================================================================================
    // 四、内部工具
    // ==================================================================================

    /** 重算扣分合计与得分（不定级——定级只在提交时发生），返回 final_score */
    private BigDecimal recompute(Long sheetId) {
        BigDecimal base = jdbc.queryForObject("select base_score from mr_qc_sheet where id = ?",
                BigDecimal.class, sheetId);
        BigDecimal deduct = jdbc.queryForObject(
                "select coalesce(sum(deduct_score), 0) from mr_qc_sheet_item where sheet_id = ?",
                BigDecimal.class, sheetId);
        if (base == null) base = new BigDecimal("100");
        if (deduct == null) deduct = BigDecimal.ZERO;
        // 扣分可能超过基础分：得分下限 0，不出现负分（负分在甲乙丙里没有语义，只会污染均分）
        BigDecimal fin = base.subtract(deduct).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        jdbc.update("update mr_qc_sheet set total_deduct = ?, final_score = ? where id = ?",
                deduct, fin, sheetId);
        return fin;
    }

    /** 读甲/乙级阈值；任一非法返回 null（调用方转 4845，不静默回落默认值） */
    private int[] thresholds() {
        try {
            int a = Integer.parseInt(configReader.get("mr.qc.grade_a_min", "90").strip());
            int b = Integer.parseInt(configReader.get("mr.qc.grade_b_min", "80").strip());
            if (b < 0 || a > 100 || b >= a) return null;
            return new int[]{a, b};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String grade(BigDecimal finalScore, int aMin, int bMin) {
        if (finalScore.compareTo(BigDecimal.valueOf(aMin)) >= 0) return "甲";
        if (finalScore.compareTo(BigDecimal.valueOf(bMin)) >= 0) return "乙";
        return "丙";
    }

    /** 评分单 + 明细（明细带字典名称与一级项；字典项已删则回落显示 item_code） */
    private LinkedHashMap<String, Object> loadSheet(Long admissionId) {
        var row = one("""
                select s.id, s.admission_id, s.base_score, s.total_deduct, s.final_score, s.grade,
                       s.status, s.note, s.reviewer_id, s.reviewed_at, s.created_at,
                       u.real_name as reviewer_name,
                       a.admission_no, a.status as admission_status, a.discharged_at,
                       a.archived, a.archived_at,
                       p.name as patient_name, d.name as dept_name
                from mr_qc_sheet s
                join inp_admission a on a.id = s.admission_id
                join empi_patient p on p.id = a.patient_id
                join sys_dept d on d.id = a.dept_id
                left join sys_user u on u.id = s.reviewer_id
                where s.admission_id = ?
                """, admissionId);
        if (row == null) return null;
        var m = new LinkedHashMap<>(row);
        m.put("items", jdbc.queryForList("""
                select i.id, i.item_code, coalesce(t.name, i.item_code) as item_name,
                       coalesce(t.category, '（字典已删）') as category,
                       i.deduct_score, i.source, i.remark
                from mr_qc_sheet_item i
                left join mr_qc_item t on t.code = i.item_code
                where i.sheet_id = ?
                order by t.category, t.sort_no, i.item_code
                """, ((Number) row.get("id")).longValue()));
        m.put("archivedAtCaveat", ARCHIVED_AT_CAVEAT);
        return m;
    }

    private LinkedHashMap<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : new LinkedHashMap<>(rows.get(0));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /**
     * CSV 字段转义 —— 与 StatsController.csv / PrintReportController.csv 逐字同款（含公式注入守卫）：
     * 扣分项名称是维护端可写入的自由文本，以 = + - @ 开头会被 Excel 当公式执行；含逗号会串列。
     * 数值不加 ' 前缀（加了在 Excel 里变文本，SUM 跳过，合计对不上）。
     * 本轮不改 StatsController（并行车道占用），故在此自带一份；抽公共工具类留作后续小重构。
     */
    private static String csv(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (!(v instanceof Number) && !s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return s.contains(",") || s.contains("\"") || s.contains("\n")
                ? "\"" + s.replace("\"", "\"\"") + "\""
                : s;
    }
}
