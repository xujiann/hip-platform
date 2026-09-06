package cn.hip.outpatient.service;

import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.service.ConfigReader;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * v51 车道 B：重复用药与同类药检查。
 *
 * <h2>三个层次（难度递增，全部实现）</h2>
 * <ol>
 *   <li><b>SAME_DRUG 完全相同</b>——同一 {@code md_drug.id} 开两次。永远可用，不依赖任何维护数据。</li>
 *   <li><b>SAME_GENERIC 同通用名</b>——不同厂家/规格的同一个药。依赖
 *       {@code md_drug.generic_name}，V153 只加列不填值。</li>
 *   <li><b>SAME_CATEGORY 同药理类别</b>——依赖 {@code md_drug.pharm_category_code}
 *       与字典表 {@code cdss_pharm_category}，同样只加列不填值。</li>
 * </ol>
 *
 * <h2>第 ②③ 层在本仓没有数据源，这一点不掩饰</h2>
 * 编码前实测 {@code md_drug} 全部列，<b>没有通用名列、没有药理分类列</b>。
 * 两个看着像但不是的列：{@code drug_class}(W 西药/C 中成药) 是费用分类，
 * {@code antibiotic}/{@code abx_level} 是抗菌药分级管理。
 * 因此 V153 加了两个<b>可空</b>列、<b>一行都不填</b>，{@code null} 一律判为
 * <b>不可比较</b>并计入 {@code unmaintained} 如实回报——不是判为「不同类」。
 *
 * <p><b>绝不按药品名做字符串匹配。</b>「阿莫西林胶囊」与「阿莫西林颗粒」是同通用名，
 * 但「阿莫西林」与「阿莫西林克拉维酸钾」<b>不是</b>——后者是复方制剂，前缀匹配会把一个
 * 合理处方判成重复。误报的代价不止是这一次报错：医生学会无视重复用药提示之后，
 * 第 ① 层那条真正有用的规则也一起废了。通用名走<b>精确相等</b>（仅去首尾空白）。
 *
 * <h2>同次就诊 vs 跨处方：性质不同，处置不同</h2>
 * <ul>
 *   <li><b>IN_VISIT 同一次就诊开重了</b>——一张处方上同一个药出现两次，
 *       几乎总是开单失误（改方时忘删旧行）。{@code SAME_DRUG}/{@code SAME_GENERIC}
 *       在 gate=block 时可拦（5620/5621）。</li>
 *   <li><b>CROSS_VISIT 上次的药还没吃完又开</b>——<b>只提示不拦</b>。
 *       因为<b>长期用药复诊续方是正常医疗行为</b>：高血压患者每月来开同一种降压药，
 *       若一律报重复，这条规则一个月就被医生完全无视。</li>
 * </ul>
 *
 * <p>跨处方因此<b>不按「窗口内开过就报」判定</b>，而按<b>上次的药有没有吃完</b>判定：
 * 用完日 = 上次挂号 {@code visit_date} + 上次医嘱 {@code days}。今天已过用完日 →
 * 正常续方，<b>一条提示都不出</b>；未到用完日 → 尚有 N 天用量未用完，出 WARN；
 * {@code days} 未填 → <b>不猜</b>，出 INFO 并明说无法区分续方与重复。
 * lookback 天数只决定往前翻多少天，不决定判没判重。
 *
 * <p><b>同药理类别即便在同次就诊也不拦</b>：同类联用常常是有意为之
 * （两种不同机理的降压药联合达标是指南推荐方案），做成硬拦截会拦掉正确处方。
 *
 * <h2>既有链路一个字节没动</h2>
 * {@link CdssService} 的三类规则与 {@code CdssController} 的 4 个端点逐字未改，
 * 错误码 4015/4017/4650 原样。本类不写 {@code cdss_alert}——那是既有 DDI/疗程提醒的
 * 留痕表，混进重复用药会污染既有报表口径；本版走独立台账 {@code cdss_duplicate_alert}。
 *
 * <h2>错误码 5620–5639（本车道子段）</h2>
 * <ul>
 *   <li>5620 同次就诊重复用药——完全相同药品（仅 gate=block）</li>
 *   <li>5621 同次就诊重复用药——同通用名（仅 gate=block）</li>
 *   <li>5622 入参非法（挂号不存在 / 药品清单为空 / 维护参数为空）</li>
 *   <li>5623 药理类别码未定义</li>
 *   <li>5624 药品不存在</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DuplicateRxService {

    /** 重复用药 gate 配置键（V153 已 seed，默认 warn） */
    public static final String GATE_KEY = "cdss.gate.duplicate";

    /** 跨处方回溯天数配置键（V153 已 seed，默认 30） */
    public static final String LOOKBACK_KEY = "cdss.duplicate.lookback.days";

    public static final int LOOKBACK_DEFAULT = 30;
    public static final int LOOKBACK_MIN = 1;
    public static final int LOOKBACK_MAX = 180;

    public static final int ERR_IN_VISIT_SAME_DRUG = 5620;
    public static final int ERR_IN_VISIT_SAME_GENERIC = 5621;
    public static final int ERR_BAD_ARG = 5622;
    public static final int ERR_UNKNOWN_CATEGORY = 5623;
    public static final int ERR_DRUG_NOT_FOUND = 5624;

    public static final String SCOPE_IN_VISIT = "IN_VISIT";
    public static final String SCOPE_CROSS_VISIT = "CROSS_VISIT";
    public static final String LEVEL_SAME_DRUG = "SAME_DRUG";
    public static final String LEVEL_SAME_GENERIC = "SAME_GENERIC";
    public static final String LEVEL_SAME_CATEGORY = "SAME_CATEGORY";
    public static final String SEV_BLOCK = "BLOCK";
    public static final String SEV_WARN = "WARN";
    public static final String SEV_INFO = "INFO";

    private static final int ALERT_LIMIT_DEFAULT = 200;
    private static final int ALERT_LIMIT_MAX = 1000;
    private static final int MESSAGE_MAX = 500;

    private final JdbcTemplate jdbc;
    private final ConfigReader configReader;

    public static class DuplicateRxException extends HipBizException {
        public DuplicateRxException(int code, String message) {
            super(code, message);
        }
    }

    // ==================================================================
    // gate 与配置
    // ==================================================================

    /**
     * 三态 gate 解析。
     *
     * <p><b>坏配置回落 warn 而非 off</b>：把 'blocked'、'true'、'1' 这类写错的值当 off，
     * 等于让一个笔误静默关掉重复用药校验；回落 warn 至少还会喊一声、落一行台账。
     */
    public String gate() {
        String v = configReader.get(GATE_KEY, SEV_WARN.toLowerCase(Locale.ROOT));
        v = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "off", "warn", "block" -> v;
            default -> "warn";
        };
    }

    /** 跨处方回溯天数；越界或非法一律回落默认值（配置写错不该让检索窗口变成 0 天或十年） */
    public int lookbackDays() {
        int n = configReader.getInt(LOOKBACK_KEY, LOOKBACK_DEFAULT);
        return (n < LOOKBACK_MIN || n > LOOKBACK_MAX) ? LOOKBACK_DEFAULT : n;
    }

    // ==================================================================
    // 数据结构
    // ==================================================================

    /**
     * 一条重复用药发现。
     *
     * @param remainingDays 跨处方时 = 上次 visit_date + 上次 days - 今天；null 表示上次未填疗程天数
     * @param blocking      该条在 gate=block 时是否会拦截（仅同次就诊的完全相同/同通用名为 true）
     */
    public record Finding(String scope, String matchLevel, String severity,
                          Long newDrugId, String newDrugName,
                          Long priorDrugId, String priorDrugName,
                          Long priorRegistrationId, LocalDate priorVisitDate,
                          Integer priorDays, Integer remainingDays,
                          boolean blocking, String message) {
    }

    private record DrugMeta(long id, String name, String genericName, String categoryCode) {
    }

    /** 一条待比对的既往/在用药品行；registrationId 为 null 表示同次就诊 */
    private record PriorLine(long drugId, String drugName, Integer days,
                             Long registrationId, LocalDate visitDate) {
    }

    // ==================================================================
    // 一、评估（纯只读，不看 gate、不拦、不落痕）
    // ==================================================================

    /**
     * 重复用药预览：如实返回全部三层发现。
     *
     * <p><b>预览永远返回完整结果，不受 gate 影响</b>——gate 管的是「开单要不要被拦」，
     * 一个显式的查询接口不该因为 gate=off 就假装什么都没查到。
     */
    public Map<String, Object> evaluate(Long registrationId, List<Long> newDrugIds) {
        if (registrationId == null) {
            throw new DuplicateRxException(ERR_BAD_ARG, "挂号 ID 不能为空");
        }
        List<Long> drugIds = newDrugIds == null ? List.of()
                : newDrugIds.stream().filter(java.util.Objects::nonNull).toList();

        var regRows = jdbc.queryForList(
                "select patient_id, visit_date from outp_registration where id = ?", registrationId);
        if (regRows.isEmpty()) {
            throw new DuplicateRxException(ERR_BAD_ARG, "挂号不存在：" + registrationId);
        }
        long patientId = ((Number) regRows.get(0).get("patient_id")).longValue();

        var body = new LinkedHashMap<String, Object>();
        body.put("registrationId", registrationId);
        body.put("patientId", patientId);
        body.put("gate", gate());
        body.put("lookbackDays", lookbackDays());
        body.put("findings", List.of());
        body.put("warnings", List.of());
        if (drugIds.isEmpty()) {
            body.put("note", "本次未提交任何药品，无需检查");
            return body;
        }

        int lookback = lookbackDays();
        LocalDate today = BusinessDates.today();
        LocalDate from = today.minusDays(lookback);

        List<PriorLine> inVisit = inVisitLines(registrationId);
        List<PriorLine> crossVisit = crossVisitLines(patientId, registrationId, from, today);

        // 一次把涉及到的药全部取出来（本次新开 ∪ 同次在用 ∪ 窗口内既往）
        Set<Long> involved = new LinkedHashSet<>(drugIds);
        inVisit.forEach(l -> involved.add(l.drugId()));
        crossVisit.forEach(l -> involved.add(l.drugId()));
        Map<Long, DrugMeta> metas = drugMetas(involved);
        Map<String, String> categoryNames = categoryNames();

        List<Finding> findings = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // --- 同次就诊：本次新开 vs（同诊在用 ∪ 本次其它新开行）---
        // 本次提交内部的重复（同一张处方上写了两行同一个药）也要抓，它与「忘删旧行」同源。
        // 每个新行都必须占一个位置（含 md_drug 里查不到的），否则下标错位会让某一行跟自己比，
        // 凭空报出一条 SAME_DRUG。
        List<PriorLine> inVisitAll = new ArrayList<>(inVisit);
        List<Long> unknownDrugIds = new ArrayList<>();
        for (Long id : drugIds) {
            DrugMeta m = metas.get(id);
            if (m == null && !unknownDrugIds.contains(id)) {
                unknownDrugIds.add(id);
            }
            inVisitAll.add(new PriorLine(id, m == null ? ("#" + id) : m.name(), null, null, null));
        }
        for (int i = 0; i < drugIds.size(); i++) {
            DrugMeta nd = metas.get(drugIds.get(i));
            if (nd == null) continue;
            int newLineIdx = inVisit.size() + i;   // 该新行自己在 inVisitAll 中的位置
            for (int j = 0; j < inVisitAll.size(); j++) {
                if (j == newLineIdx) continue;     // 不跟自己比
                PriorLine p = inVisitAll.get(j);
                DrugMeta pm = metas.get(p.drugId());
                if (pm == null) continue;
                String level = matchLevel(nd, pm);
                if (level == null) continue;
                // 无序对去重：A-vs-B 与 B-vs-A 是同一件事，不该报两遍
                String key = SCOPE_IN_VISIT + "|" + Math.min(nd.id(), pm.id())
                        + "|" + Math.max(nd.id(), pm.id()) + "|" + level;
                if (!seen.add(key)) continue;
                findings.add(inVisitFinding(nd, pm, level, categoryNames));
            }
        }

        // --- 跨处方：只报「上次的药还没吃完」---
        Map<String, Finding> crossBest = new LinkedHashMap<>();
        for (Long id : drugIds) {
            DrugMeta nd = metas.get(id);
            if (nd == null) continue;
            for (PriorLine p : crossVisit) {
                DrugMeta pm = metas.get(p.drugId());
                if (pm == null) continue;
                String level = matchLevel(nd, pm);
                if (level == null) continue;
                Finding f = crossVisitFinding(nd, pm, p, level, today, categoryNames);
                if (f == null) continue;   // 药已吃完 → 正常续方，不报
                String key = SCOPE_CROSS_VISIT + "|" + nd.id() + "|" + pm.id() + "|" + level;
                Finding prev = crossBest.get(key);
                if (prev == null || moreRelevant(f, prev)) {
                    crossBest.put(key, f);
                }
            }
        }
        findings.addAll(crossBest.values());
        findings.sort((a, b) -> severityRank(b.severity()) - severityRank(a.severity()));

        body.put("findings", findings);
        body.put("warnings", findings.stream().map(Finding::message).toList());

        // --- 覆盖率如实回报：第 ②③ 层查不出东西时必须让使用方看得见 ---
        List<String> noGeneric = new ArrayList<>();
        List<String> noCategory = new ArrayList<>();
        for (Long id : involved) {
            DrugMeta m = metas.get(id);
            if (m == null) continue;
            if (norm(m.genericName()) == null) noGeneric.add(m.name());
            if (norm(m.categoryCode()) == null) noCategory.add(m.name());
        }
        var unmaintained = new LinkedHashMap<String, Object>();
        unmaintained.put("involvedDrugCount", involved.size());
        unmaintained.put("missingGenericName", noGeneric);
        unmaintained.put("missingPharmCategory", noCategory);
        // md_drug 里查不到的药品 ID 如实回报，不静默丢弃——静默丢弃会让「查过了没问题」与
        // 「压根没查」在返回体里长得一模一样。
        unmaintained.put("unknownDrugIds", unknownDrugIds);
        body.put("unmaintained", unmaintained);
        body.put("note", "missingGenericName / missingPharmCategory 里的药品未维护通用名或药理类别"
                + "（md_drug.generic_name / pharm_category_code 为空），第 ②③ 层对它们**查不出重复**"
                + "——这不代表它们没有同名同类药。不按药名猜：「阿莫西林」与「阿莫西林克拉维酸钾」名字相近但不是同一个药。");
        return body;
    }

    // ==================================================================
    // 二、开单校验（受 gate 管辖，可拦、落台账）
    // ==================================================================

    /**
     * 开单前重复用药校验：gate=off 整段旁路；warn 放行但回带 warnings 并落台账；
     * block 仅拦「同次就诊 + 完全相同/同通用名」。
     *
     * <p><b>台账的已知缺口</b>：block 档拦下的那一次由业务异常回滚，台账里看不到。
     * 要统计拦截次数须用 REQUIRES_NEW 独立事务，而独立事务会在 {@code @Transactional}
     * 单测中真提交、污染既有用例，须配套改测试基座，本版不做（见 V153 表注释）。
     * warn 档的放行记录是完整可信的，而 warn→block 的决策依据恰恰来自 warn 档数据。
     */
    @Transactional
    public Map<String, Object> checkOnOrdering(Long registrationId, List<Long> newDrugIds) {
        String gate = gate();
        if ("off".equals(gate)) {
            var body = new LinkedHashMap<String, Object>();
            body.put("registrationId", registrationId);
            body.put("gate", gate);
            body.put("skipped", true);
            body.put("findings", List.of());
            body.put("warnings", List.of());
            body.put("note", "cdss.gate.duplicate=off，重复用药校验整段旁路（预览端点不受此影响）");
            return body;
        }

        Map<String, Object> body = evaluate(registrationId, newDrugIds);
        @SuppressWarnings("unchecked")
        List<Finding> findings = (List<Finding>) body.get("findings");
        boolean block = "block".equals(gate);
        List<Finding> blocking = findings.stream().filter(Finding::blocking).toList();

        if (!findings.isEmpty()) {
            long patientId = ((Number) body.get("patientId")).longValue();
            Timestamp now = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
            for (Finding f : findings) {
                jdbc.update("""
                        insert into cdss_duplicate_alert
                            (registration_id, patient_id, gate, scope, match_level, severity,
                             new_drug_id, new_drug_name, prior_drug_id, prior_drug_name,
                             prior_registration_id, prior_visit_date, prior_days, remaining_days,
                             blocked, message, created_at)
                        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """,
                        registrationId, patientId, gate, f.scope(), f.matchLevel(), f.severity(),
                        f.newDrugId(), f.newDrugName(), f.priorDrugId(), f.priorDrugName(),
                        f.priorRegistrationId(), f.priorVisitDate(), f.priorDays(), f.remainingDays(),
                        block && f.blocking(), trim(f.message(), MESSAGE_MAX), now);
            }
        }

        body.put("blocked", block && !blocking.isEmpty());
        if (block && !blocking.isEmpty()) {
            boolean sameDrug = blocking.stream().anyMatch(f -> LEVEL_SAME_DRUG.equals(f.matchLevel()));
            int code = sameDrug ? ERR_IN_VISIT_SAME_DRUG : ERR_IN_VISIT_SAME_GENERIC;
            String detail = String.join("；", blocking.stream().map(Finding::message).toList());
            throw new DuplicateRxException(code,
                    "重复用药拦截（gate " + GATE_KEY + "=block）：" + detail);
        }
        if (!findings.isEmpty()) {
            body.put("note", "gate=" + gate + " 已放行，" + findings.size()
                    + " 条重复用药提示已回带并记入台账 cdss_duplicate_alert；"
                    + "跨处方与同药理类别即便 gate=block 也只提示不拦（见 DuplicateRxService 类注释）");
        }
        return body;
    }

    // ==================================================================
    // 三、匹配与判定
    // ==================================================================

    /**
     * 三层匹配，最具体者优先。
     *
     * <p>通用名与类别码<b>精确相等</b>：既不做前缀也不做相似度。null/空白一律判为
     * <b>不可比较</b>（返回不匹配），绝不当成「同为未维护所以是同一类」——
     * 那会让所有未维护的药互相报重复。
     */
    private String matchLevel(DrugMeta a, DrugMeta b) {
        if (a.id() == b.id()) {
            return LEVEL_SAME_DRUG;
        }
        String ga = norm(a.genericName());
        if (ga != null && ga.equals(norm(b.genericName()))) {
            return LEVEL_SAME_GENERIC;
        }
        String ca = norm(a.categoryCode());
        if (ca != null && ca.equals(norm(b.categoryCode()))) {
            return LEVEL_SAME_CATEGORY;
        }
        return null;
    }

    private Finding inVisitFinding(DrugMeta nd, DrugMeta pm, String level, Map<String, String> categoryNames) {
        // 同药理类别不拦：同类联用常是有意为之（两种不同机理的降压药联合达标是指南推荐方案）
        boolean blocking = !LEVEL_SAME_CATEGORY.equals(level);
        String severity = blocking ? SEV_BLOCK : SEV_WARN;
        String msg = switch (level) {
            case LEVEL_SAME_DRUG -> "【重复用药·同次就诊】%s 在本次就诊中重复开具（完全相同药品）"
                    .formatted(nd.name());
            case LEVEL_SAME_GENERIC -> "【重复用药·同次就诊】%s 与 %s 通用名相同（%s），属重复用药"
                    .formatted(nd.name(), pm.name(), norm(nd.genericName()));
            default -> "【同类用药·同次就诊】%s 与 %s 同属药理类别「%s」；同类联用可能是有意为之，请确认（不拦截）"
                    .formatted(nd.name(), pm.name(), categoryLabel(nd.categoryCode(), categoryNames));
        };
        return new Finding(SCOPE_IN_VISIT, level, severity, nd.id(), nd.name(), pm.id(), pm.name(),
                null, null, null, null, blocking, msg);
    }

    /**
     * 跨处方判定：<b>按上次的药有没有吃完</b>，不是按「窗口内开过就报」。
     *
     * @return null 表示上次的药按疗程已用完——那是<b>正常复诊续方</b>，一条提示都不出
     */
    private Finding crossVisitFinding(DrugMeta nd, DrugMeta pm, PriorLine p, String level,
                                      LocalDate today, Map<String, String> categoryNames) {
        Integer priorDays = p.days();
        Integer remaining = null;
        if (priorDays != null && priorDays > 0 && p.visitDate() != null) {
            LocalDate exhaust = p.visitDate().plusDays(priorDays);
            long left = ChronoUnit.DAYS.between(today, exhaust);
            if (left <= 0) {
                return null;   // 药已吃完：长期用药复诊续方是正常医疗行为，不是重复用药
            }
            remaining = (int) left;
        }
        String label = levelLabel(level, nd, categoryNames);
        String severity = remaining == null ? SEV_INFO : SEV_WARN;
        String msg = remaining == null
                ? "【重复用药·跨处方】本次开具 %s；%s 就诊已开 %s（%s），但该次医嘱**未填疗程天数**，"
                        .formatted(nd.name(), p.visitDate(), pm.name(), label)
                        + "无法判断是正常续方还是重复开药，请人工确认（不拦截）"
                : "【重复用药·跨处方】本次开具 %s；%s 就诊已开 %s（%s，疗程 %d 天），按疗程推算尚有 %d 天用量未用完，本次再开可能重复（不拦截）"
                        .formatted(nd.name(), p.visitDate(), pm.name(), label, priorDays, remaining);
        return new Finding(SCOPE_CROSS_VISIT, level, severity, nd.id(), nd.name(), pm.id(), pm.name(),
                p.registrationId(), p.visitDate(), priorDays, remaining, false, msg);
    }

    /** 同一对药可能命中多张既往处方；留最值得看的那条，不把五次历史都糊到医生脸上 */
    private boolean moreRelevant(Finding candidate, Finding current) {
        int bySeverity = severityRank(candidate.severity()) - severityRank(current.severity());
        if (bySeverity != 0) return bySeverity > 0;
        int a = candidate.remainingDays() == null ? -1 : candidate.remainingDays();
        int b = current.remainingDays() == null ? -1 : current.remainingDays();
        if (a != b) return a > b;
        if (candidate.priorVisitDate() == null || current.priorVisitDate() == null) return false;
        return candidate.priorVisitDate().isAfter(current.priorVisitDate());
    }

    private static int severityRank(String severity) {
        return switch (severity) {
            case SEV_BLOCK -> 3;
            case SEV_WARN -> 2;
            default -> 1;
        };
    }

    private String levelLabel(String level, DrugMeta nd, Map<String, String> categoryNames) {
        return switch (level) {
            case LEVEL_SAME_DRUG -> "完全相同药品";
            case LEVEL_SAME_GENERIC -> "同通用名 " + norm(nd.genericName());
            default -> "同药理类别 " + categoryLabel(nd.categoryCode(), categoryNames);
        };
    }

    private String categoryLabel(String code, Map<String, String> categoryNames) {
        String c = norm(code);
        if (c == null) return "未维护";
        String name = categoryNames.get(c);
        return name == null ? c : name + "(" + c + ")";
    }

    // ==================================================================
    // 四、取数
    // ==================================================================

    /** 同次就诊在用药品（口径与既有 CdssService 对齐：DRUG 且非 CANCELLED） */
    private List<PriorLine> inVisitLines(Long registrationId) {
        var rows = jdbc.queryForList("""
                select item_id, item_name, days
                from outp_order
                where registration_id = ? and order_type = 'DRUG' and status <> 'CANCELLED'
                order by id
                """, registrationId);
        return rows.stream().map(r -> new PriorLine(
                ((Number) r.get("item_id")).longValue(),
                (String) r.get("item_name"),
                r.get("days") == null ? null : ((Number) r.get("days")).intValue(),
                null, null)).toList();
    }

    /**
     * 窗口内该患者的既往处方（排除本次挂号与已退号挂号）。
     *
     * <p>日期窗口用 {@link BusinessDates#today()} 推算后作为参数传入，
     * 不用 SQL 的 {@code current_date}——两者按不同时区切日，本仓已为此炸过四次。
     * 不排除「今天」：同一天在不同科室挂两次号是真实存在的重复开药场景。
     */
    private List<PriorLine> crossVisitLines(long patientId, Long currentRegId, LocalDate from, LocalDate to) {
        var rows = jdbc.queryForList("""
                select o.item_id, o.item_name, o.days, r.id as reg_id, r.visit_date
                from outp_order o
                join outp_registration r on r.id = o.registration_id
                where r.patient_id = ?
                  and r.id <> ?
                  and r.status <> 'CANCELLED'
                  and r.visit_date >= ? and r.visit_date <= ?
                  and o.order_type = 'DRUG' and o.status <> 'CANCELLED'
                order by r.visit_date desc, o.id desc
                """, patientId, currentRegId, from, to);
        return rows.stream().map(r -> new PriorLine(
                ((Number) r.get("item_id")).longValue(),
                (String) r.get("item_name"),
                r.get("days") == null ? null : ((Number) r.get("days")).intValue(),
                ((Number) r.get("reg_id")).longValue(),
                toLocalDate(r.get("visit_date")))).toList();
    }

    private Map<Long, DrugMeta> drugMetas(Set<Long> ids) {
        var map = new LinkedHashMap<Long, DrugMeta>();
        if (ids.isEmpty()) return map;
        String in = String.join(",", ids.stream().map(x -> "?").toList());
        var rows = jdbc.queryForList(
                "select id, name, generic_name, pharm_category_code from md_drug where id in (" + in + ")",
                ids.toArray());
        for (var r : rows) {
            long id = ((Number) r.get("id")).longValue();
            map.put(id, new DrugMeta(id, (String) r.get("name"),
                    (String) r.get("generic_name"), (String) r.get("pharm_category_code")));
        }
        return map;
    }

    private Map<String, String> categoryNames() {
        var map = new LinkedHashMap<String, String>();
        for (var r : jdbc.queryForList("select code, name from cdss_pharm_category")) {
            map.put((String) r.get("code"), (String) r.get("name"));
        }
        return map;
    }

    // ==================================================================
    // 五、主数据维护（药剂科自己填，本平台一行都不替它猜）
    // ==================================================================

    /** 维护药品通用名；传空字符串表示撤回维护（置回 null = 未维护） */
    @Transactional
    public Map<String, Object> setGenericName(Long drugId, String genericName) {
        String name = requireDrug(drugId);
        String value = norm(genericName);
        jdbc.update("update md_drug set generic_name = ? where id = ?", value, drugId);
        var body = new LinkedHashMap<String, Object>();
        body.put("drugId", drugId);
        body.put("drugName", name);
        body.put("genericName", value);
        body.put("note", value == null
                ? "已置为未维护：该药品在同通用名层将被判为不可比较（不是「无同名药」）"
                : "通用名按精确相等比较；复方制剂请填复方本位名（如「阿莫西林克拉维酸钾」），"
                        + "不要只填主成分——那会把它与单方阿莫西林误判成同一个药");
        return body;
    }

    /** 维护药品药理类别；传空字符串表示撤回维护 */
    @Transactional
    public Map<String, Object> setPharmCategory(Long drugId, String categoryCode) {
        String name = requireDrug(drugId);
        String code = norm(categoryCode);
        if (code != null) {
            Integer n = jdbc.queryForObject(
                    "select count(*) from cdss_pharm_category where code = ?", Integer.class, code);
            if (n == null || n == 0) {
                throw new DuplicateRxException(ERR_UNKNOWN_CATEGORY,
                        "药理类别码未定义：" + code + "（请先在 cdss_pharm_category 建类别，本平台不预置药理分类）");
            }
        }
        jdbc.update("update md_drug set pharm_category_code = ? where id = ?", code, drugId);
        var body = new LinkedHashMap<String, Object>();
        body.put("drugId", drugId);
        body.put("drugName", name);
        body.put("pharmCategoryCode", code);
        return body;
    }

    /** 新建/改名药理类别（本平台不预置任何药理分类内容，全部由药剂科按院内用药目录维护） */
    @Transactional
    public Map<String, Object> upsertCategory(String code, String name, String remark) {
        String c = norm(code);
        String n = norm(name);
        if (c == null || n == null) {
            throw new DuplicateRxException(ERR_BAD_ARG, "类别码与类别名称均不能为空");
        }
        jdbc.update("""
                insert into cdss_pharm_category (code, name, remark) values (?,?,?)
                on conflict (code) do update set name = excluded.name, remark = excluded.remark
                """, c, n, norm(remark));
        var body = new LinkedHashMap<String, Object>();
        body.put("code", c);
        body.put("name", n);
        return body;
    }

    public List<Map<String, Object>> categories() {
        return jdbc.queryForList("""
                select c.code, c.name, c.remark, c.created_at,
                       (select count(*) from md_drug d where d.pharm_category_code = c.code) as drug_count
                from cdss_pharm_category c order by c.code
                """);
    }

    private String requireDrug(Long drugId) {
        if (drugId == null) {
            throw new DuplicateRxException(ERR_BAD_ARG, "药品 ID 不能为空");
        }
        var rows = jdbc.queryForList("select name from md_drug where id = ?", drugId);
        if (rows.isEmpty()) {
            throw new DuplicateRxException(ERR_DRUG_NOT_FOUND, "药品不存在：" + drugId);
        }
        return (String) rows.get(0).get("name");
    }

    // ==================================================================
    // 六、口径自述与台账
    // ==================================================================

    /** gate / 窗口 / <b>维护覆盖率</b>：第 ②③ 层能查出多少，取决于药剂科维护了多少 */
    public Map<String, Object> config() {
        var cov = jdbc.queryForMap("""
                select count(*) filter (where enabled) as enabled_drugs,
                       count(*) filter (where enabled and generic_name is not null
                                        and btrim(generic_name) <> '') as generic_maintained,
                       count(*) filter (where enabled and pharm_category_code is not null) as category_maintained
                from md_drug
                """);
        var body = new LinkedHashMap<String, Object>();
        body.put("gateKey", GATE_KEY);
        body.put("gate", gate());
        body.put("lookbackKey", LOOKBACK_KEY);
        body.put("lookbackDays", lookbackDays());
        body.put("coverage", cov);
        body.put("levels", List.of(
                Map.of("level", LEVEL_SAME_DRUG, "dataSource", "md_drug.id",
                        "available", true, "blockable", true,
                        "note", "完全相同药品；不依赖任何维护数据，永远可用"),
                Map.of("level", LEVEL_SAME_GENERIC, "dataSource", "md_drug.generic_name",
                        "available", ((Number) cov.get("generic_maintained")).longValue() > 0, "blockable", true,
                        "note", "同通用名；V153 只加列不填值，未维护的药在本层查不出重复。精确相等比较，不做前缀/相似度匹配"),
                Map.of("level", LEVEL_SAME_CATEGORY, "dataSource", "md_drug.pharm_category_code",
                        "available", ((Number) cov.get("category_maintained")).longValue() > 0, "blockable", false,
                        "note", "同药理类别；同样只加列不填值。即便 gate=block 也不拦——同类联用常是有意为之")));
        body.put("scopes", List.of(
                Map.of("scope", SCOPE_IN_VISIT, "blockable", true,
                        "note", "同一次就诊开重了；改方忘删旧行，几乎总是失误"),
                Map.of("scope", SCOPE_CROSS_VISIT, "blockable", false,
                        "note", "上次的药还没吃完又开；只提示不拦。按「上次 visit_date + days 是否已过」判定，"
                                + "已吃完的正常复诊续方一条提示都不出")));
        body.put("knownGap", "本平台不预置任何药学知识：通用名与药理类别属院内用药目录，"
                + "须由药剂科逐条维护。cdss_pharm_category 出厂只有一条标注为占位的示例行。");
        return body;
    }

    public List<Map<String, Object>> alerts(Long registrationId, Integer limit) {
        int n = (limit == null || limit <= 0) ? ALERT_LIMIT_DEFAULT : Math.min(limit, ALERT_LIMIT_MAX);
        if (registrationId != null) {
            return jdbc.queryForList(
                    "select * from cdss_duplicate_alert where registration_id = ? order by id desc limit ?",
                    registrationId, n);
        }
        return jdbc.queryForList("select * from cdss_duplicate_alert order by id desc limit ?", n);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 空白视同未维护；仅去首尾空白（导入残留的归一），不做任何语义加工 */
    private static String norm(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static LocalDate toLocalDate(Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof LocalDate d) return d;
        return LocalDate.parse(v.toString());
    }
}
