package cn.hip.outpatient.service;

import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.config.HipProfiles;
import cn.hip.platform.core.service.ConfigReader;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * v51 车道 C：CDSS 特殊人群用药规则引擎（妊娠 / 哺乳 / 肝功能不全 / 肾功能不全）。
 *
 * <h2>一、先讲数据源核实结果——这是本车道最重要的产出</h2>
 * <ol>
 *   <li><b>妊娠 / 哺乳状态：全仓此前无任何数据源</b>（实测，非推测）。{@code empi_patient}(V2)
 *       无孕产字段，{@code outp_registration}(V3) / {@code inp_admission}(V8) 同样没有，
 *       全仓对 {@code 妊娠|孕|怀孕|pregnan|gravid|lactat|哺乳} 的检索零命中。
 *       故 V154 新建 {@code cdss_population_status} 作为**人工申报**的唯一来源。
 *       <p><b>不按性别 + 年龄推定「育龄女性即可能妊娠」。</b> 那不是"稍微吵一点"：
 *       门诊育龄女性占比极高，每张处方都弹一次，医生三天内就会连同真阳性一起无视整个提示区，
 *       用假阳性换覆盖率的净效果为负。</li>
 *   <li><b>肝肾功能：有真实数据源，但只覆盖门诊</b>。可达路径
 *       {@code outp_lab_result}(V10) → {@code outp_order} → {@code outp_registration} → 患者，
 *       由 {@code LabResultListener} 落库。<b>住院侧没有任何检验结果表</b>
 *       （{@code outp_lab_result.order_id} 外键指向 {@code outp_order}，{@code inp_order} 的
 *       结果全仓无处落），住院患者的肝肾规则取不到数——如实标注，本版补不了。</li>
 *   <li><b>eGFR 一律不自算。</b> CKD-EPI / MDRD 有多个版本（含/不含种族系数、2021 去种族版），
 *       系数记错不报错，只会静默算出偏高的 eGFR，把该减量的患者判成正常。本类<b>不含任何医学公式</b>：
 *       阈值与项目代码由药剂科在规则行里配，引擎只做「取最近一次数值结果、和配置阈值比大小」。</li>
 *   <li><b>年龄维度不碰</b>：既有 {@code cdss_age_rule} 与错误码 4017 已覆盖，一个字节不动。</li>
 * </ol>
 *
 * <h2>二、规则内容不由代码提供</h2>
 * 「哪个药孕妇禁用」「肌酐高到多少要减量」属药学知识，由药剂科按院内用药目录维护。
 * 本类只有引擎，V154 种子只有一条 {@code enabled=false} 且条件永不成立的示例行。
 * 编出来的规则看起来非常专业，医生会信，然后出事。宁可空表。
 *
 * <h2>三、三个"不知道"必须与"没问题"区分开</h2>
 * 本引擎最容易写错的地方，是把「取不到数据」静默当成「没问题」。三处都单独回带 notice：
 * <ul>
 *   <li><b>状态未采集</b>（无申报 / 已过期 / 已撤销）→ 妊娠与哺乳规则<b>整类不评估</b>，
 *       且<b>只在确实有规则会命中这些药时</b>才提示，否则每张处方都挂一条废话。
 *       {@code NEITHER}（问过了、不是）静默放行、不提示——这正是 NEITHER 必须能记的理由。</li>
 *   <li><b>检验结果缺失 / 超期 / 非数值</b>（如 {@code >200}、{@code 阴性}）→ 该规则不评估并提示。
 *       {@code >200} 不做"去掉大于号当 200"：对 {@code GT 177} 是真命中，对 {@code LT 30} 是确定不命中，
 *       靠猜会两边都错。</li>
 *   <li><b>单位不一致</b>→ 该规则不评估并提示，<b>绝不做换算</b>。肌酐 mg/dL 与 μmol/L 相差约 88 倍，
 *       拿 {@code 2.4 mg/dL} 去比 {@code 177 μmol/L} 会静默判成正常——这是"看起来在保护、
 *       实际上什么都没拦"的最坏形态。</li>
 * </ul>
 *
 * <h2>四、命中必须给依据</h2>
 * 妊娠用药警告的假阳性代价极高（不必要的恐慌与自行停药）。只说「孕妇慎用」而不给出处与级别，
 * 医生无从判断该不该采纳，最终整类提示被无视。故 {@code basis_source} / {@code basis_level}
 * 在建表期即为 {@code not null} 且非空白（{@code ck_cdss_pop_rule_basis}）：<b>没有依据的规则插不进来</b>。
 * 命中留痕存的是依据<b>快照</b>，不 join 现值——规则被药剂科改过之后，仍要能还原医生当时看到的原话。
 *
 * <h2>五、gate {@code cdss.gate.population}，三态，默认 warn</h2>
 * 此前系统从无特殊人群校验，直接 block 会让存量处方大面积失败。但 warn 不等于静默：
 * warn 档<b>返回体带 warnings 且留痕落库</b>。坏配置回落 warn 而非 off——
 * 把 {@code 'blocked'}/{@code 'true'}/{@code '1'} 之类笔误当 off，等于让拼写错误关掉临床校验。
 *
 * <h2>六、已知边界（不藏）</h2>
 * <ul>
 *   <li><b>本类尚未接进开单链路。</b> 钩子 {@link #checkPrescription} 已按既有
 *       {@code CdssService.checkPrescription} 的形状备好，但 {@code DoctorStationService}
 *       不在本车道名下，接线是 cross_lane 事项。当前只能经本模块端点主动预检。</li>
 *   <li><b>block 档命中时的留痕会随调用方事务回滚。</b> 既有 {@code CdssService} 的 4015/4017
 *       是同样语义。此处<b>刻意不用 {@code REQUIRES_NEW}</b>：新事务做 FK 检查要在
 *       {@code outp_registration} / {@code empi_patient} 上取 {@code FOR KEY SHARE}，
 *       若调用方事务持有冲突锁就是一次<b>自死锁</b>——开单会挂住而不是报错，比丢一行留痕糟得多。
 *       补偿是把完整依据写进抛出的异常消息，临床信息不丢；留痕方案需主控统一裁决（见 cross_lane）。</li>
 *   <li><b>药名匹配沿用既有 contains 口径</b>，与 {@code cdss_ddi_rule} / {@code cdss_dose_rule} /
 *       {@code cdss_age_rule} 一致，不另发明语义（药剂科维护四张表时心智一致）。
 *       其固有缺陷（关键字过短会误配）与既有三张表同源，不在本版扩大也不在本版修。</li>
 * </ul>
 *
 * <h2>七、时间</h2>
 * 「今天」一律 {@link BusinessDates#today()}，无裸 {@code LocalDate.now()}；写入前
 * {@code truncatedTo(MICROS)}（PG timestamptz 只存微秒且四舍五入，纳秒尾数会让回读值晚最多 500ns）；
 * 回读 {@code Timestamp} 用 {@code toInstant()}，<b>不用 {@code toLocalDateTime()}</b>（后者按 JVM 时区转）。
 * 失效日比较在 Java 侧按业务时区做，SQL 里不出现 {@code current_date}（DB 时区口径会与业务"今天"错开）。
 */
@Service
@RequiredArgsConstructor
public class PopulationRuleService {

    private final JdbcTemplate jdbc;
    private final ConfigReader configReader;

    /** gate 配置键（V154 已 seed，默认 warn） */
    public static final String GATE_KEY = "cdss.gate.population";
    /** 状态申报陈旧阈值配置键（天，V154 已 seed，默认 180） */
    public static final String STALE_DAYS_KEY = "cdss.population.status.stale_days";

    private static final ZoneId ZONE = ZoneId.of(HipProfiles.ZONE);
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZONE);

    /**
     * 严格十进制。刻意<b>不</b>接受 {@code >200} / {@code <0.5} / {@code 阴性} / 科学计数：
     * 区间型结果要做区间推理才判得对，猜一个代表值必然在某个比较方向上错。判不了就说判不了。
     */
    private static final Pattern STRICT_DECIMAL = Pattern.compile("^[+-]?\\d+(\\.\\d+)?$");

    /** 人群维度稳定序：多条命中时按此序取第一条决定错误码，避免同一处方两次调用抛不同的码 */
    private static final List<String> POPULATION_ORDER =
            List.of("PREGNANCY", "LACTATION", "HEPATIC", "RENAL");

    /** 失效日合理上界：5 年。防的是 2036 打成 2026 这类录入笔误，不是临床判断 */
    private static final int VALID_UNTIL_MAX_YEARS = 5;

    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 100;

    // ==================================================================
    // gate
    // ==================================================================

    /** gate 三态解析；坏配置回落 warn（回落 off 等于让一个笔误静默关掉临床校验） */
    public String gate() {
        String v = configReader.get(GATE_KEY, "warn");
        v = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "off", "warn", "block" -> v;
            default -> "warn";
        };
    }

    private int staleDays() {
        int d = configReader.getInt(STALE_DAYS_KEY, 180);
        return d > 0 ? d : 180;
    }

    private static Instant nowMicros() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    // ==================================================================
    // 一、妊娠 / 哺乳状态申报（本系统内唯一的妊娠状态来源）
    // ==================================================================

    /**
     * 申报状态。追加写，不改历史行；同一患者的最新未撤销行胜出。
     *
     * @param status      PREGNANT / LACTATING / NEITHER（NEITHER = 问过了、不是，与"没问"不同）
     * @param source      SELF_REPORT / CLINICIAN_CONFIRMED / LAB_CONFIRMED
     * @param validUntil  失效日；PREGNANT / LACTATING 必填，过期即回落"未采集"
     */
    @Transactional
    public Long assertStatus(Long patientId, String status, String source,
                             LocalDate validUntil, String note, Long userId) {
        if (patientId == null) {
            throw new BizException(5647, "患者 id 不能为空");
        }
        String st = norm(status);
        if (!List.of("PREGNANT", "LACTATING", "NEITHER").contains(st)) {
            throw new BizException(5644, "状态只能为 PREGNANT/LACTATING/NEITHER，收到：" + status);
        }
        String src = norm(source);
        if (!List.of("SELF_REPORT", "CLINICIAN_CONFIRMED", "LAB_CONFIRMED").contains(src)) {
            throw new BizException(5648, "来源只能为 SELF_REPORT/CLINICIAN_CONFIRMED/LAB_CONFIRMED，收到：" + source);
        }
        Integer exists = jdbc.queryForObject(
                "select count(*) from empi_patient where id = ?", Integer.class, patientId);
        if (exists == null || exists == 0) {
            throw new BizException(5647, "患者不存在：" + patientId);
        }
        LocalDate today = BusinessDates.today();
        if (!"NEITHER".equals(st)) {
            // 没有失效日的妊娠标记会在产后继续拦该患者的所有处方，且没人会想起来清它。
            // 这是"一次录入、永久误拦"，比不记更糟，所以在入口就拒。
            if (validUntil == null) {
                throw new BizException(5645,
                        st + " 必须给失效日（预产期/预计哺乳结束日）：无失效日的状态会在产后继续误拦处方");
            }
            if (validUntil.isBefore(today)) {
                throw new BizException(5646, "失效日 " + validUntil + " 早于业务今天 " + today);
            }
        }
        if (validUntil != null && validUntil.isAfter(today.plusYears(VALID_UNTIL_MAX_YEARS))) {
            throw new BizException(5646,
                    "失效日 " + validUntil + " 超出 " + VALID_UNTIL_MAX_YEARS + " 年合理范围，疑为录入笔误");
        }
        Timestamp at = Timestamp.from(nowMicros());
        // `insert ... returning id`（与 EmrTemplateService / MedTechController 同口径），
        // **不是** insert 之后再 `select max(id) where patient_id = ?`：后者在两名医护同时给
        // 同一患者申报时会取回**对方刚提交的那一行**的 id，调用方随后 revokeStatus(该 id)
        // 就撤销了别人的申报——错撤一条妊娠状态是临床安全事件，不是计数误差。
        return jdbc.queryForObject("""
                insert into cdss_population_status
                    (patient_id, status, source, asserted_by, asserted_at, valid_until, note)
                values (?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, patientId, st, src, userId, at,
                validUntil == null ? null : java.sql.Date.valueOf(validUntil), trunc(note, 255));
    }

    /** 撤销一条录错的申报。软撤销：不删行、不改语义列，留痕原样保留。 */
    @Transactional
    public void revokeStatus(Long statusId, String reason, Long userId) {
        int n = jdbc.update("""
                update cdss_population_status
                   set revoked_at = ?, revoked_by = ?, revoke_reason = ?
                 where id = ? and revoked_at is null
                """, Timestamp.from(nowMicros()), userId, trunc(reason, 255), statusId);
        if (n == 0) {
            throw new BizException(5649, "申报记录不存在或已撤销：" + statusId);
        }
    }

    /** 某患者的申报历史（含已撤销与已过期，供人工核对）。 */
    public List<Map<String, Object>> statusHistory(Long patientId, Integer limit) {
        return jdbc.queryForList("""
                select s.*, u.real_name as asserted_by_name
                  from cdss_population_status s
                  left join sys_user u on u.id = s.asserted_by
                 where s.patient_id = ?
                 order by s.id desc
                 limit ?
                """, patientId, clampLimit(limit));
    }

    /**
     * 当前生效状态。
     *
     * <p><b>取最新未撤销行、再判过期，而不是在 SQL 里过滤掉过期行。</b>
     * 后者有一个会静默压制警告的陷阱：患者先申报 NEITHER（无失效日、永久有效），
     * 后申报 PREGNANT（有失效日）；等 PREGNANT 过期，带过期过滤的查询会**回落到那条更早的
     * NEITHER**，于是"未采集"被伪装成"确认非妊娠"，妊娠规则从此静默失效。
     * 正确语义是：最新那条一旦过期，整个维度回到"未采集"。
     */
    public Map<String, Object> currentStatus(Long patientId) {
        var rows = jdbc.queryForList("""
                select id, status, source, asserted_by, asserted_at, valid_until, note
                  from cdss_population_status
                 where patient_id = ? and revoked_at is null
                 order by asserted_at desc, id desc
                 limit 1
                """, patientId);
        var out = new LinkedHashMap<String, Object>();
        out.put("patientId", patientId);
        if (rows.isEmpty()) {
            out.put("status", "UNKNOWN");
            out.put("reason", "从未采集");
            return out;
        }
        var r = rows.get(0);
        LocalDate today = BusinessDates.today();
        LocalDate until = r.get("valid_until") instanceof java.sql.Date d ? d.toLocalDate() : null;
        Instant assertedAt = r.get("asserted_at") instanceof Timestamp ts ? ts.toInstant() : null;
        if (until != null && until.isBefore(today)) {
            out.put("status", "UNKNOWN");
            out.put("reason", "最近一次申报（%s，失效日 %s）已过期，视为未采集".formatted(r.get("status"), until));
            out.put("expiredStatus", r.get("status"));
            out.put("expiredValidUntil", until.toString());
            return out;
        }
        out.put("statusId", r.get("id"));
        out.put("status", r.get("status"));
        out.put("source", r.get("source"));
        out.put("assertedAt", assertedAt == null ? null : TS_FMT.format(assertedAt));
        out.put("validUntil", until == null ? null : until.toString());
        out.put("note", r.get("note"));
        if (assertedAt != null) {
            long ageDays = ChronoUnit.DAYS.between(assertedAt.atZone(ZONE).toLocalDate(), today);
            out.put("assertedDaysAgo", ageDays);
            // 三年前的「否认妊娠」永久静默压制警告，是本仓反复吃过亏的静默失败形态：
            // 不推翻它（推翻要靠临床判断），但一定要把年龄摆到医生眼前。
            out.put("stale", ageDays > staleDays());
        }
        return out;
    }

    // ==================================================================
    // 二、规则维护（药剂科）
    // ==================================================================

    public record RuleReq(String drugKeyword, String population, String severity,
                          String labItemCode, String comparator, BigDecimal threshold, String labUnit,
                          Integer labMaxAgeDays, String basisSource, String basisLevel, String message) {}

    /**
     * 新增规则。校验刻意做在服务层与 DB 约束<b>两遍</b>——规则表是临床安全数据，
     * 一条"条件写了一半"的规则要么误判要么静默跳过，值得挡两道。
     */
    @Transactional
    public Long addRule(RuleReq req, Long userId) {
        String pop = norm(req.population());
        if (!POPULATION_ORDER.contains(pop)) {
            throw new BizException(5650, "人群只能为 PREGNANCY/LACTATION/HEPATIC/RENAL，收到：" + req.population());
        }
        String sev = norm(req.severity());
        if (!List.of("FORBID", "CAUTION").contains(sev)) {
            throw new BizException(5650, "级别只能为 FORBID/CAUTION，收到：" + req.severity());
        }
        if (isBlank(req.drugKeyword()) || isBlank(req.message())) {
            throw new BizException(5654, "药品关键字与提示语不能为空");
        }
        // 没有依据的规则不许入库：只说「孕妇慎用」而不给出处与级别，医生无从判断该不该采纳，
        // 最终结果是整类提示被无视——那时连真正该看的那条也一起没人看。
        if (isBlank(req.basisSource()) || isBlank(req.basisLevel())) {
            throw new BizException(5651,
                    "必须填写依据出处（说明书版本/院内用药目录/指南名称+年份）与依据级别：无依据的提示医生无法判断是否采纳");
        }
        boolean lab = "HEPATIC".equals(pop) || "RENAL".equals(pop);
        String cmp = norm(req.comparator());
        if (lab) {
            if (isBlank(req.labItemCode()) || isBlank(cmp) || req.threshold() == null || isBlank(req.labUnit())) {
                throw new BizException(5652,
                        "肝/肾功能规则必须给全 labItemCode + comparator + threshold + labUnit"
                                + "（labUnit 必填：肌酐 mg/dL 与 μmol/L 相差约 88 倍，缺单位无法核对可比性）");
            }
            if (!List.of("LT", "LTE", "GT", "GTE").contains(cmp)) {
                throw new BizException(5650, "比较符只能为 LT/LTE/GT/GTE，收到：" + req.comparator());
            }
        } else if (!isBlank(req.labItemCode()) || !isBlank(cmp)
                || req.threshold() != null || !isBlank(req.labUnit())) {
            throw new BizException(5653, "妊娠/哺乳规则不得携带检验条件（labItemCode/comparator/threshold/labUnit）");
        }
        int maxAge = req.labMaxAgeDays() == null ? 90 : req.labMaxAgeDays();
        if (maxAge <= 0) {
            throw new BizException(5652, "检验结果可用天数必须为正，收到：" + req.labMaxAgeDays());
        }
        // 同上，`returning id`。这里的 `select max(id) from cdss_population_rule` 更危险：
        // 它连 patient_id 之类的过滤都没有，两名药师并发建规则时必然互相取错 id，
        // 随后 setRuleEnabled(ruleId, false) 会**停用对方刚建的那条规则**——
        // 停用一条临床拦截规则且无人知晓，正是本版要防的静默失效。
        return jdbc.queryForObject("""
                insert into cdss_population_rule
                    (drug_keyword, population, severity, lab_item_code, comparator, threshold, lab_unit,
                     lab_max_age_days, basis_source, basis_level, message, enabled, created_by, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?, ?)
                returning id
                """, Long.class,
                req.drugKeyword().trim(), pop, sev,
                lab ? req.labItemCode().trim() : null,
                lab ? cmp : null,
                lab ? req.threshold() : null,
                lab ? req.labUnit().trim() : null,
                maxAge, req.basisSource().trim(), req.basisLevel().trim(), trunc(req.message(), 512),
                userId, Timestamp.from(nowMicros()));
    }

    /** 停用/启用规则。规则不删——停用过的规则要能查回来解释历史留痕。 */
    @Transactional
    public void setRuleEnabled(Long ruleId, boolean enabled) {
        int n = jdbc.update("update cdss_population_rule set enabled = ? where id = ?", enabled, ruleId);
        if (n == 0) {
            throw new BizException(5655, "规则不存在：" + ruleId);
        }
    }

    public List<Map<String, Object>> listRules(String population, Boolean enabledOnly, Integer limit) {
        var sql = new StringBuilder("select * from cdss_population_rule where 1 = 1");
        var args = new ArrayList<Object>();
        if (!isBlank(population)) {
            sql.append(" and population = ?");
            args.add(norm(population));
        }
        if (Boolean.TRUE.equals(enabledOnly)) {
            sql.append(" and enabled");
        }
        sql.append(" order by population, id limit ?");
        args.add(clampLimit(limit));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    // ==================================================================
    // 三、评估（只读，不落库）
    // ==================================================================

    /**
     * 只读评估：给定患者与一组药名，返回命中、未评估提示与当前 gate。
     * 不写任何表，供医生站在提交前预检、也供药师复核。
     *
     * @param drugNames 本次拟开的药品名。特殊人群规则是<b>单药</b>规则（不像 DDI 需要两两配对），
     *                  故只看新开药：已开的药在它自己被开时已经评估过，重复评估只会重复打扰。
     */
    public Map<String, Object> evaluate(Long registrationId, Long patientId, List<String> drugNames) {
        String gate = gate();
        var body = new LinkedHashMap<String, Object>();
        body.put("gate", gate);
        body.put("registrationId", registrationId);
        body.put("patientId", patientId);

        var hits = new ArrayList<Map<String, Object>>();
        // LinkedHashSet 而非 List：同一药品命中多条引用同一检验项目的规则时，
        // "无该项目结果" 这条提示会被重复添加 N 次；提示区刷屏本身就是让人不看提示的原因之一。
        var notices = new LinkedHashSet<String>();
        body.put("hits", hits);
        body.put("notices", new ArrayList<>(notices));
        body.put("warnings", new ArrayList<String>());

        if ("off".equals(gate)) {
            notices.add("gate=off：特殊人群规则本次整体旁路，未做任何评估");
            body.put("notices", new ArrayList<>(notices));
            body.put("status", currentStatus(patientId));
            return body;
        }
        List<String> drugs = drugNames == null ? List.of()
                : drugNames.stream().filter(d -> !isBlank(d)).map(String::trim).toList();
        var status = currentStatus(patientId);
        body.put("status", status);
        if (drugs.isEmpty()) {
            return body;
        }


        var rules = jdbc.queryForList("select * from cdss_population_rule where enabled order by population, id");
        String statusValue = (String) status.get("status");
        if (Boolean.TRUE.equals(status.get("stale"))) {
            notices.add("妊娠/哺乳状态申报于 %s（距今 %s 天，超过陈旧阈值 %d 天），本次仍按该状态判定，请核对"
                    .formatted(status.get("assertedAt"), status.get("assertedDaysAgo"), staleDays()));
        }

        // 状态未采集时，妊娠/哺乳规则整类不评估。只在"确实有规则会命中这些药"时才提示——
        // 否则每张处方都挂一条与本次用药无关的废话，医生很快就不看提示区了。
        var suppressedDrugs = new LinkedHashSet<String>();
        // 检验值按 (item_code, maxAgeDays) 缓存：一张处方里多条规则常引用同一项目，不必重复查
        var labCache = new HashMap<String, Optional<Map<String, Object>>>();

        for (var rule : rules) {
            String pop = (String) rule.get("population");
            String keyword = (String) rule.get("drug_keyword");
            for (String drug : drugs) {
                if (keyword == null || !drug.contains(keyword)) {
                    continue;
                }
                if ("PREGNANCY".equals(pop) || "LACTATION".equals(pop)) {
                    String need = "PREGNANCY".equals(pop) ? "PREGNANT" : "LACTATING";
                    if ("UNKNOWN".equals(statusValue)) {
                        suppressedDrugs.add(drug);
                        continue;
                    }
                    if (!need.equals(statusValue)) {
                        continue;   // NEITHER 或另一态：问过了、不适用，静默放行
                    }
                    hits.add(hit(rule, drug, "患者状态 %s（来源 %s，申报于 %s，失效日 %s）".formatted(
                            statusValue, status.get("source"), status.get("assertedAt"), status.get("validUntil"))));
                } else {
                    evaluateLabRule(rule, drug, patientId, labCache, hits, notices);
                }
            }
        }
        if (!suppressedDrugs.isEmpty()) {
            notices.add("患者妊娠/哺乳状态未采集（%s），涉及 %s 的妊娠/哺乳期用药规则本次未评估——请先采集状态再判断"
                    .formatted(status.getOrDefault("reason", "从未采集"), String.join("、", suppressedDrugs)));
        }
        hits.sort(Comparator
                .comparingInt((Map<String, Object> h) -> POPULATION_ORDER.indexOf((String) h.get("population")))
                .thenComparing(h -> ((Number) h.get("ruleId")).longValue()));
        body.put("notices", new ArrayList<>(notices));
        // warn 档必须**真的把话说给医生**：命中项原文进 warnings，由调用方原样透出。
        // 静默的 warn 与 off 没有任何区别，那才是最坏的一档。
        body.put("warnings", hits.stream().map(h -> (Object) h.get("text")).toList());
        return body;
    }

    /** 肝/肾功能规则：取最近一次该项目的数值型结果，与配置阈值比较。三种"判不了"都出提示，不静默。 */
    private void evaluateLabRule(Map<String, Object> rule, String drug, Long patientId,
                                 Map<String, Optional<Map<String, Object>>> cache,
                                 List<Map<String, Object>> hits, Set<String> notices) {
        String item = (String) rule.get("lab_item_code");
        int maxAge = ((Number) rule.get("lab_max_age_days")).intValue();
        String dim = "HEPATIC".equals(rule.get("population")) ? "肝功能" : "肾功能";
        var found = cache.computeIfAbsent(item + "|" + maxAge, k -> latestLab(patientId, item, maxAge));
        if (found.isEmpty()) {
            notices.add("%s规则未评估（%s）：近 %d 天内无项目 %s 的检验结果。住院医嘱的检验结果全仓无落库表，住院患者此维度取不到数"
                    .formatted(dim, drug, maxAge, item));
            return;
        }
        var lab = found.get();
        String raw = String.valueOf(lab.get("result_value"));
        BigDecimal value = parseStrictDecimal(raw);
        if (value == null) {
            // ">200" 对 GT 是真命中、对 LT 是确定不命中，猜一个代表值必然在某个方向上错
            notices.add("%s规则未评估（%s）：项目 %s 最近一次结果为非数值型「%s」，不做区间推测"
                    .formatted(dim, drug, item, raw));
            return;
        }
        String ruleUnit = (String) rule.get("lab_unit");
        String labUnit = (String) lab.get("unit");
        String unitNote = "";
        if (!isBlank(labUnit) && !isBlank(ruleUnit) && !normUnit(labUnit).equals(normUnit(ruleUnit))) {
            // 绝不换算：肌酐 mg/dL 与 μmol/L 差约 88 倍，静默比较会把该减量的患者判成正常
            notices.add("%s规则未评估（%s）：项目 %s 结果单位「%s」与规则阈值单位「%s」不一致，本引擎不做单位换算"
                    .formatted(dim, drug, item, labUnit, ruleUnit));
            return;
        }
        if (isBlank(labUnit)) {
            unitNote = "；结果未带单位，未能核对与阈值单位（" + ruleUnit + "）的一致性";
            notices.add("%s规则（%s）：项目 %s 最近一次结果未带单位，已按阈值单位 %s 直接比较，请人工核对"
                    .formatted(dim, drug, item, ruleUnit));
        }
        BigDecimal threshold = (BigDecimal) rule.get("threshold");
        int c = value.compareTo(threshold);
        boolean matched = switch ((String) rule.get("comparator")) {
            case "LT" -> c < 0;
            case "LTE" -> c <= 0;
            case "GT" -> c > 0;
            case "GTE" -> c >= 0;
            default -> false;
        };
        if (!matched) {
            return;
        }
        Instant at = lab.get("created_at") instanceof Timestamp ts ? ts.toInstant() : null;
        hits.add(hit(rule, drug, "%s %s%s（%s，报告于 %s）%s %s".formatted(
                lab.get("item_name") == null ? item : lab.get("item_name"),
                value.toPlainString(), isBlank(labUnit) ? "" : labUnit,
                item, at == null ? "时间未知" : TS_FMT.format(at),
                cmpText((String) rule.get("comparator")), threshold.toPlainString() + ruleUnit) + unitNote));
    }

    /**
     * 最近一次该项目的检验结果。
     *
     * <p><b>只取最新那一条，不"往前找到第一条能解析的"。</b> 最新结果解析不出来时回落到更旧的数值，
     * 等于拿过时的肾功能替今天做决定，且不会有任何迹象——那正是本引擎要避免的静默失败。
     *
     * <p>时间窗用 {@code Instant} 计算并作为参数传入，SQL 里不出现 {@code now()} / {@code current_date}：
     * 那两者按 DB 时区，与业务"今天"是两套口径。
     */
    private Optional<Map<String, Object>> latestLab(Long patientId, String itemCode, int maxAgeDays) {
        Timestamp since = Timestamp.from(nowMicros().minus(maxAgeDays, ChronoUnit.DAYS));
        var rows = jdbc.queryForList("""
                select lr.id, lr.item_code, lr.item_name, lr.result_value, lr.unit,
                       lr.abnormal_flag, lr.created_at, lr.order_id
                  from outp_lab_result lr
                  join outp_order o on o.id = lr.order_id
                  join outp_registration reg on reg.id = o.registration_id
                 where reg.patient_id = ?
                   and lr.item_code is not null
                   and upper(btrim(lr.item_code)) = upper(btrim(?))
                   and lr.created_at >= ?
                 order by lr.created_at desc, lr.id desc
                 limit 1
                """, patientId, itemCode, since);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private Map<String, Object> hit(Map<String, Object> rule, String drug, String evidence) {
        var h = new LinkedHashMap<String, Object>();
        h.put("ruleId", rule.get("id"));
        h.put("drugName", drug);
        h.put("population", rule.get("population"));
        h.put("severity", rule.get("severity"));
        h.put("message", rule.get("message"));
        h.put("basisSource", rule.get("basis_source"));
        h.put("basisLevel", rule.get("basis_level"));
        h.put("evidence", evidence);
        // 一句话把"哪条规则、什么级别、依据出处、命中证据"全给出来。
        // 只说「孕妇慎用」的提示，医生无从判断该不该采纳。
        h.put("text", "【%s·%s】%s：%s（依据：%s；级别：%s；证据：%s；规则 #%s）".formatted(
                popText((String) rule.get("population")),
                "FORBID".equals(rule.get("severity")) ? "禁用" : "慎用",
                drug, rule.get("message"),
                rule.get("basis_source"), rule.get("basis_level"), evidence, rule.get("id")));
        return h;
    }

    // ==================================================================
    // 四、开单钩子
    // ==================================================================

    /**
     * 开单前校验。<b>形状与既有 {@code CdssService#checkPrescription} 对齐</b>，接线时只需在
     * {@code DoctorStationService} 里增加一行调用，既有三类规则与 4015/4017/4650 一个字节不动。
     * {@code DoctorStationService} 不在本车道名下，接线是 cross_lane 事项。
     *
     * <p>gate 语义：
     * <ul>
     *   <li>{@code off}：整体旁路，不评估、不落库、不提示。</li>
     *   <li>{@code warn}（默认）：<b>不拦截，但留痕落库、且把 warnings 返回给调用方</b>。
     *       warn 不等于静默——静默的 warn 与 off 没有区别。</li>
     *   <li>{@code block}：命中 {@code FORBID} 抛 5640–5643（按人群维度分码）；
     *       {@code CAUTION} 仍只留痕不拦。</li>
     * </ul>
     *
     * <p><b>block 档的留痕会随调用方事务一起回滚</b>，与既有 {@code CdssService} 4015/4017 同语义。
     * 此处刻意不开 {@code REQUIRES_NEW}：新事务的 FK 检查要在 {@code outp_registration} /
     * {@code empi_patient} 上取 {@code FOR KEY SHARE}，若调用方事务持有冲突锁就会自死锁——
     * 开单挂住比丢一行留痕糟得多。补偿是把完整依据写进异常消息（见下），临床信息不丢。
     *
     * @return 评估结果；warn 档下 {@code warnings} 非空即表示有命中，调用方须原样透出给医生
     */
    @Transactional
    public Map<String, Object> checkPrescription(Long registrationId, Long patientId, List<String> drugNames) {
        var result = evaluate(registrationId, patientId, drugNames);
        String gate = (String) result.get("gate");
        if ("off".equals(gate)) {
            return result;
        }
        @SuppressWarnings("unchecked")
        var hits = (List<Map<String, Object>>) result.get("hits");
        if (hits.isEmpty()) {
            return result;
        }
        Map<String, Object> blocking = "block".equals(gate)
                ? hits.stream().filter(h -> "FORBID".equals(h.get("severity"))).findFirst().orElse(null)
                : null;
        for (var h : hits) {
            boolean blocked = blocking != null
                    && ((Number) h.get("ruleId")).longValue() == ((Number) blocking.get("ruleId")).longValue()
                    && h.get("drugName").equals(blocking.get("drugName"));
            jdbc.update("""
                    insert into cdss_population_alert
                        (registration_id, patient_id, rule_id, drug_name, population, severity,
                         gate, blocked, basis_source, basis_level, message, evidence, occurred_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, registrationId, patientId, h.get("ruleId"), trunc((String) h.get("drugName"), 128),
                    h.get("population"), h.get("severity"), gate, blocked,
                    trunc((String) h.get("basisSource"), 255), trunc((String) h.get("basisLevel"), 64),
                    trunc((String) h.get("message"), 512), trunc((String) h.get("evidence"), 512),
                    Timestamp.from(nowMicros()));
        }
        if (blocking != null) {
            // 消息里带全依据：block 档的留痕会随本次回滚消失，医生看到的这句话是唯一的载体
            throw new BizException(codeOf((String) blocking.get("population")),
                    "CDSS 拦截" + blocking.get("text"));
        }
        return result;
    }

    /** 5640 妊娠 / 5641 哺乳 / 5642 肝功能 / 5643 肾功能——按人群维度分码，便于下游分别处置 */
    private static int codeOf(String population) {
        return switch (population) {
            case "PREGNANCY" -> 5640;
            case "LACTATION" -> 5641;
            case "HEPATIC" -> 5642;
            case "RENAL" -> 5643;
            default -> 5640;
        };
    }

    // ==================================================================
    // 五、留痕查询
    // ==================================================================

    public List<Map<String, Object>> alerts(Long patientId, Integer limit) {
        if (patientId == null) {
            return jdbc.queryForList("""
                    select a.*, p.name as patient_name from cdss_population_alert a
                      join empi_patient p on p.id = a.patient_id
                     order by a.id desc limit ?
                    """, clampLimit(limit));
        }
        return jdbc.queryForList("""
                select a.*, p.name as patient_name from cdss_population_alert a
                  join empi_patient p on p.id = a.patient_id
                 where a.patient_id = ?
                 order by a.id desc limit ?
                """, patientId, clampLimit(limit));
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /** 严格十进制解析；非严格数值一律返回 null（由调用方出"未评估"提示，不猜） */
    static BigDecimal parseStrictDecimal(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        return STRICT_DECIMAL.matcher(s).matches() ? new BigDecimal(s) : null;
    }

    /** 单位归一：仅去空白与大小写差异（μ/u 之类不做等价，那已经是换算判断） */
    static String normUnit(String u) {
        return u == null ? "" : u.trim().replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private static String cmpText(String c) {
        return switch (c) {
            case "LT" -> "低于";
            case "LTE" -> "不高于";
            case "GT" -> "高于";
            case "GTE" -> "不低于";
            default -> c;
        };
    }

    private static String popText(String p) {
        return switch (p) {
            case "PREGNANCY" -> "妊娠期";
            case "LACTATION" -> "哺乳期";
            case "HEPATIC" -> "肝功能不全";
            case "RENAL" -> "肾功能不全";
            default -> p;
        };
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trunc(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }

    private static int clampLimit(Integer limit) {
        int n = limit == null ? DEFAULT_LIMIT : limit;
        if (n <= 0) {
            throw new BizException(5656, "limit 必须为正，收到：" + limit);
        }
        return Math.min(n, MAX_LIMIT);
    }
}
