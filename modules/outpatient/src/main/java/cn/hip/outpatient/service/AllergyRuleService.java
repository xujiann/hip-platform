package cn.hip.outpatient.service;

import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.core.service.ConfigReader;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * v51 车道 A：CDSS 过敏规则——结构化过敏原 / 交叉过敏族 / 开单过敏审查 / 自由文本人工迁移工作台。
 *
 * <h2>本类补的是一个临床安全缺口，不是功能缺口</h2>
 * 实测：{@code empi_patient.allergy_history} 是 varchar(512) 自由文本，全仓 6 处只把它
 * select 出来显示，<b>从没进过任何一条 CDSS 规则</b>。{@link CdssService} 仅 94 行、
 * 三类规则（DDI / 疗程 / 年龄），「过敏」两字零命中。也就是说
 * <b>给青霉素过敏的患者开阿莫西林，此前系统一声不吭地放行</b>。
 *
 * <h2>与既有 CDSS 的边界（一个字节没改）</h2>
 * {@code CdssService.checkPrescription} 的三类规则、{@code CdssController} 的 4 个端点、
 * 错误码 4015/4017/4650 逐字未动。本类走新表 {@code cdss_allergen*} /
 * {@code cdss_patient_allergy} 与新前缀 {@code /api/cdss/allergy/**}。
 * 唯一与既有对象的交集是<b>往 {@code cdss_alert} 里追加 rule_type='ALLERGY' 的行</b>——
 * 这是刻意的：既有 {@code GET /api/cdss/alerts} 因此不改一行代码就能看到过敏提醒，
 * 而 warn 档最怕的就是提示只活在一次 HTTP 响应里、事后谁也查不到。
 *
 * <h2>一、不内置任何药学知识</h2>
 * 过敏原目录、过敏原→药品映射、交叉过敏族与族间风险，<b>内容全部由药剂科按院内用药目录维护</b>。
 * 本类只提供引擎与维护端点，代码与迁移里<b>没有一条具体的药学判断</b>
 * （V152 的两条示例行 enabled=false 且无映射，命中不了任何处方）。
 * 编出来的配伍/过敏知识看起来很专业，医生会信，然后出事。
 *
 * <h2>二、自由文本绝不脚本解析</h2>
 * 「青霉素过敏」与「青霉素皮试阴性」文本相近而语义相反；「否认药物过敏史」「无」「未询问」
 * 三者也完全不同（最后一个是<b>没问过</b>，不是没过敏）。关键词或正则必然同时制造
 * 假阴性与假阳性，而假阳性的代价不止是麻烦，见下条。故存量文本一律走
 * {@link #reviewText} 的<b>人工确认工作台</b>，且 {@code allergy_history} 原文
 * <b>本模块永不写入</b>，只读。
 *
 * <h2>三、假阴性与假阳性的权衡——本版正面处理的核心问题</h2>
 * 过敏拦截里「该拦没拦」当然致命；但<b>「不该拦却拦」泛滥同样致命</b>，
 * 因为它的杀伤是间接的：医生发现十次提示有七次不成立，就会养成不读内容、
 * 直接点「继续」的肌肉记忆，于是<b>第八次那个真警告也被一起点掉</b>。
 * 一个被无视的拦截等于没有拦截，而且还骗走了本可以投在别处的注意力。
 * 本版的取舍是四条具体规则，不是一句口号：
 * <ol>
 *   <li><b>命中依据只认显式映射，不做药名子串匹配。</b>既有 DDI 用的
 *       {@code drugName.contains(...)} 搬过来会两头出错：匹配不到「青霉素过敏 vs 阿莫西林」
 *       （正是本版要堵的漏拦），又会把「碘过敏 vs 碘伏消毒液」这类不相干的一并拦下。
 *       映射由药剂科逐条维护，宁可覆盖面小而准，也不要一张全是噪音的大网。</li>
 *   <li><b>直接命中按 gate 处置；交叉命中恒为警告、三档下永不拦截。</b>
 *       交叉过敏是概率性的，同族并非必然交叉。把交叉做成硬拦，会让一个青霉素过敏的患者
 *       用不了任何头孢——那是大量临床上完全合理的处方，也正是「无脑点继续」习惯的最大来源。
 *       交叉提示照样回带、照样落 {@code cdss_alert}，只是不夺走医生的决定权。</li>
 *   <li><b>每条提示都必须说清「凭什么」</b>：哪条过敏记录、谁在何时确认、来源是自述还是皮试、
 *       映射是成分级还是类别级。一句光秃秃的「过敏」逼医生盲目点继续，
 *       一句「患者 2024-03 皮试证实青霉素过敏（喉头水肿），本药同为青霉素类」
 *       他能就地判断。可判断的提示才不会被批量无视。</li>
 *   <li><b>覆盖不全时明说不全，绝不用沉默冒充安全。</b>「未发现过敏禁忌」只有在
 *       该患者自由文本已人工核对完、且其全部结构化过敏原都有药品映射时才成立。
 *       否则返回体照样带 warnings 说明缺口在哪（{@code unreviewedText} /
 *       {@code unmappedAllergens}）——这是本版对付假阴性的主要手段：
 *       与其假装拦得住，不如让医生知道这次审查根本没覆盖到他。</li>
 * </ol>
 *
 * <h2>四、gate 默认 warn</h2>
 * {@code cdss.gate.allergy} 三态、默认 warn、坏配置回落 warn（不是 off——
 * 不能让一个笔误静默关掉过敏审查）。默认 warn 而非 block 的理由不是「过敏没那么重要」，
 * 而是本平台此前从无过敏校验：全部存量患者的结构化过敏原数为 0、映射表为空，
 * 直接 block 会在映射填到一半时表现为「系统随机拦人」，医生的第一反应是把 gate 关掉，
 * 那样连 warn 都没有了。收紧到 block 的依据是覆盖率与核对完成度这两个数
 * （{@link #rulesOverview} 的 coverage 段可查），不是拍脑袋。
 * <b>但 warn 档必须真的把提示给到医生</b>：返回体带 warnings + 写 {@code cdss_alert}
 * + 写 {@code cdss_allergy_gate_log}，三处都不静默。
 *
 * <h2>五、本版如实留的缺口</h2>
 * <ul>
 *   <li><b>开单主链路尚未接入</b>：{@code DoctorStationService.createOrders}（车道 A 名下无此文件）
 *       第 715 行调 {@code cdssService.checkPrescription} 之后，需增<b>一行</b>调用
 *       {@link #enforceOnOrdering}——该处 {@code drug.getId()} 就在手边（第 707 行循环内），
 *       无需按药名反查。在接上之前，过敏审查只能经本类的
 *       {@code POST /api/cdss/allergy/check} 端点调用。已写进 cross_lane。</li>
 *   <li><b>前端未显示 warnings</b>：{@code frontend/shell/src/**} 是共用文件，本车道不改。
 *       warn 档若前端不显示 warnings，这一版的过敏提示对医生就是不存在的——
 *       这是本车道交付价值的必要条件，已作为 cross_lane 第一条。</li>
 *   <li><b>住院医嘱侧未接入</b>：住院开医嘱走 inpatient 模块，不在本车道名下。
 *       {@code cdss_allergy_gate_log.registration_id} 特意可空就是给它留的位。</li>
 *   <li><b>不做过敏原目录的初始内容</b>：见上文第一条。空目录 = 一条也拦不住，
 *       这个状态被返回体明说，不伪装成安全。</li>
 * </ul>
 *
 * <h2>错误码 5600–5614</h2>（本车道子段 5600–5619，5615–5619 空置未启用）
 * <ul>
 *   <li>5600 患者不存在</li>
 *   <li>5601 过敏原参数非法（不存在 / 已停用 / 编码重复 / 类型或粒度取值非法 / 名称为空）</li>
 *   <li>5602 过敏原-药品映射参数非法（药品不存在 / 重复登记 / 映射不存在 / 级别非法）</li>
 *   <li>5603 交叉过敏族参数非法（族不存在 / 编码重复 / 成员重复 / 两族相同 / 风险对重复 / 级别非法）</li>
 *   <li>5604 患者过敏记录参数非法（严重程度非法 / 来源非法 / 字段超长）</li>
 *   <li>5605 该患者已有同一过敏原的生效记录</li>
 *   <li>5606 无法识别当前登录用户（过敏记录必须有人签字，不兜匿名）</li>
 *   <li>5607 过敏记录不存在或不属于该患者</li>
 *   <li>5608 过敏记录已撤销，状态不允许该操作</li>
 *   <li>5609 撤销过敏记录必须填写原因</li>
 *   <li>5610 迁移工作台处置参数非法（结论取值非法 / 判定为过敏却未选过敏原 / 判定非过敏却选了过敏原）</li>
 *   <li>5611 该患者自由文本过敏史为空，无需核对</li>
 *   <li>5612 开药命中患者过敏原，禁止开具（<b>只有 block 档才返</b>，warn 档改走 warnings）</li>
 *   <li>5613 迁移工作台：提交的原文快照与当前过敏史不一致，须重新核对</li>
 *   <li>5614 审查参数非法（药品清单为空 / 超上限 / 药品不存在）</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AllergyRuleService {

    private final JdbcTemplate jdbc;
    private final ConfigReader configReader;
    /** 仅用于 block 档台账的 REQUIRES_NEW 写入，见 {@link #writeGateLog} */
    private final PlatformTransactionManager transactionManager;

    /** 开药过敏审查 gate 配置键（V152 已 seed，默认值 warn） */
    public static final String GATE_KEY = "cdss.gate.allergy";

    /** 一次审查最多送审的药品数：超限直接返 5614，不静默截断（截断 = 后面的药没审却报了「已审」） */
    private static final int MAX_DRUGS = 100;

    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 100;

    private static final Set<String> ALLERGEN_TYPES = Set.of("DRUG", "FOOD", "OTHER");
    private static final Set<String> DRUG_LEVELS = Set.of("INGREDIENT", "PRODUCT", "CLASS");
    private static final Set<String> SEVERITIES = Set.of("MILD", "MODERATE", "SEVERE", "UNKNOWN");
    private static final Set<String> SOURCES = Set.of("SELF_REPORT", "CLINICAL", "TEST", "TEXT_REVIEW", "OTHER");
    private static final Set<String> RISK_LEVELS = Set.of("HIGH", "MEDIUM", "LOW");
    private static final Set<String> RESOLUTIONS = Set.of("STRUCTURED", "NO_ALLERGY", "UNCLEAR");

    // ==================================================================
    // 零、gate 与时刻
    // ==================================================================

    /**
     * 过敏审查 gate 三态解析。
     *
     * <p><b>坏配置回落 warn 而不是 off</b>：把 'blocked'、'true'、'on' 这类写错的值当成 off，
     * 等于让一个笔误静默关掉全院的过敏审查。回落 warn 至少还会提示、还会留痕。
     */
    public String gate() {
        String v = configReader.get(GATE_KEY, "warn");
        v = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "off", "warn", "block" -> v;
            default -> "warn";
        };
    }

    /**
     * 全仓时刻纪律：{@code Instant.now().truncatedTo(MICROS)}。
     * PG 的 timestamptz 只存到微秒，纳秒会被静默舍入——写进去 X.123456789、读出来 X.123457，
     * 于是「写什么读什么」的断言在真库上必红（本仓已为此付过三次学费）。
     */
    private static Timestamp nowTs() {
        return Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    // ==================================================================
    // 一、开单过敏审查（本车道的正题）
    // ==================================================================

    /**
     * 过敏审查（<b>纯只读</b>，不落任何数据）：给「患者 + 本次要开的药品 id 清单」出结论。
     *
     * <p>命中分两类，处置不同：
     * <ul>
     *   <li>{@code directHits} 直接命中——该药在患者某条生效过敏记录的显式映射里。按 gate 处置。</li>
     *   <li>{@code crossHits} 交叉命中——该药映射到的过敏原，与患者过敏原同处一对有交叉风险的族。
     *       <b>恒为警告，三档下都不拦。</b>理由见类注释第三节第 2 条。</li>
     * </ul>
     *
     * <p>返回体永远带 {@code coverage}：这次审查到底覆盖了什么、没覆盖什么。
     * 没有它，「未发现过敏禁忌」就是一句谎话——目录空、映射空、原文没核对时，
     * 这个引擎本来就什么也拦不住。
     */
    public Map<String, Object> evaluate(Long patientId, List<Long> drugIds) {
        var patient = patient(patientId);
        List<Long> ids = normalizeDrugIds(drugIds);

        // 药品必须存在：id 打错时静默查不到 = 静默不审，属最坏的一种假阴性
        var drugs = jdbc.queryForList(
                "select id, name from md_drug where id in (" + placeholders(ids.size()) + ")", ids.toArray());
        if (drugs.size() != ids.size()) {
            var found = drugs.stream().map(d -> ((Number) d.get("id")).longValue()).toList();
            var missing = ids.stream().filter(i -> !found.contains(i)).toList();
            throw new BizException(5614, "药品不存在，无法完成过敏审查：" + missing);
        }

        var direct = queryDirectHits(patientId, ids);
        var directDrugIds = new LinkedHashSet<Long>();
        direct.forEach(h -> directDrugIds.add(((Number) h.get("drug_id")).longValue()));
        // 同一个药已被直接命中就不再报交叉：两条说的是同一件事，重复提示是噪音的源头之一
        var cross = queryCrossHits(patientId, ids).stream()
                .filter(h -> !directDrugIds.contains(((Number) h.get("drug_id")).longValue()))
                .toList();

        var coverage = coverage(patientId, patient.get("allergy_history"));
        String gate = gate();

        var warnings = new ArrayList<String>();
        var directOut = new ArrayList<Map<String, Object>>();
        for (var h : direct) {
            String msg = directMessage(h, gate);
            warnings.add(msg);
            directOut.add(hitBody(h, "DIRECT", msg));
        }
        var crossOut = new ArrayList<Map<String, Object>>();
        for (var h : cross) {
            String msg = crossMessage(h);
            warnings.add(msg);
            crossOut.add(hitBody(h, "CROSS", msg));
        }
        // 覆盖缺口提示：命中为空时**尤其**要说，那正是「没拦住」与「没什么可拦」被混淆的时刻
        warnings.addAll(coverageWarnings(coverage));

        boolean blocked = "block".equals(gate) && !directOut.isEmpty();

        var body = new LinkedHashMap<String, Object>();
        body.put("patientId", patientId);
        body.put("patientName", patient.get("name"));
        body.put("gate", gate);
        body.put("drugCount", ids.size());
        body.put("directHits", directOut);
        body.put("crossHits", crossOut);
        body.put("blocked", blocked);
        body.put("coverage", coverage);
        body.put("warnings", warnings);
        body.put("note", "directHits 按 gate 处置（block 档返 5612）；crossHits 三档下一律只警告不拦截——"
                + "交叉过敏是概率性的，硬拦会让青霉素过敏患者用不了任何头孢，"
                + "医生随之养成无脑点继续的习惯，真警告也会被一起点掉。"
                + "coverage.trustworthy=false 时，「未发现过敏禁忌」这句话不成立");
        return body;
    }

    /**
     * 开单时的过敏审查<b>执行版</b>：评估 + 留痕 + block 档抛 5612。
     *
     * <p>这是给开单链路调的方法（{@code DoctorStationService.createOrders} 接入点见类注释）。
     * 与 {@link #evaluate} 的差别只有「留痕 + 抛异常」，判定逻辑同一份，不存在两套口径。
     *
     * <p><b>三档都留痕，命中与否都留痕</b>：只记拦下来的那部分，
     * 「一共审了多少次 / 命中多少次 / warn 档放行了多少次」这三个数就永远算不出来，
     * 也就永远没有依据决定何时收紧到 block（v48 病理双签、v50 发药核对两次学到的同一课）。
     *
     * <p>本方法<b>刻意不加 {@code @Transactional}</b>：它要么参与调用方的事务
     * （开单链路的正常形态），要么独立执行。block 档的台账另走 REQUIRES_NEW，见 {@link #writeGateLog}。
     */
    public Map<String, Object> enforceOnOrdering(Long patientId, Long registrationId,
                                                 List<Long> drugIds, Long operatorId) {
        var result = evaluate(patientId, drugIds);
        String gate = (String) result.get("gate");
        @SuppressWarnings("unchecked")
        var directHits = (List<Map<String, Object>>) result.get("directHits");
        @SuppressWarnings("unchecked")
        var crossHits = (List<Map<String, Object>>) result.get("crossHits");
        @SuppressWarnings("unchecked")
        var coverage = (Map<String, Object>) result.get("coverage");
        boolean blocked = (boolean) result.get("blocked");

        // off 档也照常留痕与写 alert：旁路的是「拦不拦」，不是「记不记」。
        // 把 off 做成什么都不发生，就再也回答不了「关掉这段时间到底有多少处方本该被提醒」。
        if (registrationId != null) {
            for (var h : directHits) writeAlert(registrationId, "block".equals(gate) ? "FORBID" : "CAUTION", h);
            for (var h : crossHits) writeAlert(registrationId, "CAUTION", h);
        }
        writeGateLog(patientId, registrationId, gate, (int) result.get("drugCount"),
                directHits.size(), crossHits.size(), blocked, coverage,
                summarize(directHits, crossHits), operatorId, blocked);

        if (blocked) {
            throw new BizException(5612, "过敏禁忌，禁止开具：" + summarize(directHits, List.of())
                    + "（gate " + GATE_KEY + "=block）");
        }
        return result;
    }

    /** 直接命中：本次药品 ∈ 患者某条生效过敏记录的显式药品映射 */
    private List<Map<String, Object>> queryDirectHits(Long patientId, List<Long> drugIds) {
        var args = new ArrayList<Object>();
        args.add(patientId);
        args.addAll(drugIds);
        return jdbc.queryForList("""
                select pa.id            as allergy_id,
                       pa.allergen_id   as allergen_id,
                       a.name           as allergen_name,
                       a.allergen_type  as allergen_type,
                       pa.severity      as severity,
                       pa.manifestation as manifestation,
                       pa.source        as source,
                       pa.confirmed_at  as confirmed_at,
                       u.real_name      as confirmed_by_name,
                       m.drug_id        as drug_id,
                       d.name           as drug_name,
                       m.mapped_level   as mapped_level
                from cdss_patient_allergy pa
                join cdss_allergen a       on a.id = pa.allergen_id
                join cdss_allergen_drug m  on m.allergen_id = pa.allergen_id
                join md_drug d             on d.id = m.drug_id
                left join sys_user u       on u.id = pa.confirmed_by
                where pa.patient_id = ? and pa.status = 'ACTIVE'
                  and m.drug_id in (""" + placeholders(drugIds.size()) + """
                )
                order by pa.id, m.drug_id
                """, args.toArray());
    }

    /**
     * 本次药品中会被<b>直接命中</b>（∈ 患者某条生效过敏记录的显式药品映射）的 id 集合。
     *
     * <p>v55 合版修 D1 时加的**药品级信号**，给 {@code DoctorStationService} 裁决关键词闸
     * 该对哪些行让路：让路的前提必须是「这一行引擎必定会说话」，而引擎只命中显式映射的药——
     * 患者级 {@code coverage.trustworthy} 说明不了这一点（只映射一支药它就为 true）。
     * 空入参返空集，<b>不把空列表拼进 {@code in ()}</b>。
     */
    public java.util.Set<Long> directlyHitDrugIds(Long patientId, List<Long> drugIds) {
        List<Long> ids = drugIds == null ? List.of()
                : drugIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return java.util.Set.of();
        var out = new LinkedHashSet<Long>();
        for (var h : queryDirectHits(patientId, ids)) out.add(((Number) h.get("drug_id")).longValue());
        return out;
    }

    /** 患者是否有至少一条生效（ACTIVE）的结构化过敏记录。「人工核对为无过敏」时为 false。 */
    public boolean hasActiveAllergen(Long patientId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from cdss_patient_allergy where patient_id = ? and status = 'ACTIVE'",
                Integer.class, patientId);
        return n != null && n > 0;
    }

    /**
     * 交叉命中：患者过敏原所在族 —(有交叉风险)— 对方族 —(族成员)— 对方过敏原 —(映射)— 本次药品。
     *
     * <p>无序对只存一行（lo&lt;hi），故 join 两个方向都要走一遍，再用 case 取「对方族」。
     * 存两行的写法迟早只删一半，变成「从 A 查得到、从 B 查不到」的静默漏提示（同 v50 LASA）。
     */
    private List<Map<String, Object>> queryCrossHits(Long patientId, List<Long> drugIds) {
        var args = new ArrayList<Object>();
        args.add(patientId);
        args.addAll(drugIds);
        return jdbc.queryForList("""
                select distinct
                       pa.id            as allergy_id,
                       pa.allergen_id   as allergen_id,
                       a.name           as allergen_name,
                       a.allergen_type  as allergen_type,
                       pa.severity      as severity,
                       pa.manifestation as manifestation,
                       pa.source        as source,
                       pa.confirmed_at  as confirmed_at,
                       u.real_name      as confirmed_by_name,
                       g1.name          as group_name,
                       g2.name          as cross_group_name,
                       x.risk_level     as risk_level,
                       x.note           as cross_note,
                       xa.name          as cross_allergen_name,
                       m.drug_id        as drug_id,
                       d.name           as drug_name,
                       m.mapped_level   as mapped_level
                from cdss_patient_allergy pa
                join cdss_allergen a                on a.id = pa.allergen_id
                join cdss_allergen_group_member gm  on gm.allergen_id = pa.allergen_id
                join cdss_allergen_group g1         on g1.id = gm.group_id
                join cdss_allergen_cross x          on x.group_id_lo = gm.group_id
                                                    or x.group_id_hi = gm.group_id
                join cdss_allergen_group g2
                     on g2.id = case when x.group_id_lo = gm.group_id
                                     then x.group_id_hi else x.group_id_lo end
                join cdss_allergen_group_member gm2 on gm2.group_id = g2.id
                join cdss_allergen xa               on xa.id = gm2.allergen_id
                join cdss_allergen_drug m           on m.allergen_id = xa.id
                join md_drug d                      on d.id = m.drug_id
                left join sys_user u                on u.id = pa.confirmed_by
                where pa.patient_id = ? and pa.status = 'ACTIVE'
                  and m.drug_id in (""" + placeholders(drugIds.size()) + """
                )
                order by pa.id, m.drug_id
                """, args.toArray());
    }

    /**
     * 覆盖度：这次审查到底能不能被信任。
     *
     * <p>两个缺口都会让引擎<b>安静地什么也拦不住</b>，因此必须单独算出来、单独说出来：
     * <ul>
     *   <li>{@code unreviewedText}——患者有自由文本过敏史，但没有匹配<b>当前原文</b>的人工核对记录。
     *       注意是匹配当前原文：医生改过过敏史之后，旧的核对结论就对不上了，患者应重回待办。</li>
     *   <li>{@code unmappedAllergens}——患者的生效过敏原里，有几个一条药品映射都没有。
     *       映射为空的过敏原永远不会命中，它躺在患者档案里只是好看。</li>
     * </ul>
     */
    private Map<String, Object> coverage(Long patientId, Object allergyHistory) {
        String raw = allergyHistory == null ? null : String.valueOf(allergyHistory);
        boolean hasText = raw != null && !raw.isBlank();

        Integer reviewed = hasText ? jdbc.queryForObject("""
                select count(*) from cdss_allergy_text_review where patient_id = ? and source_text = ?
                """, Integer.class, patientId, raw) : 0;
        boolean unreviewedText = hasText && (reviewed == null || reviewed == 0);

        // UNCLEAR 结论也算「已核对」——人确实看过了；但它不等于「无过敏」，
        // 故 latestResolution 一并回带，让前端能把「看过说不准」与「看过确实没有」区分开
        String latestResolution = hasText ? firstString(jdbc.queryForList("""
                select resolution from cdss_allergy_text_review
                where patient_id = ? and source_text = ? order by reviewed_at desc, id desc limit 1
                """, patientId, raw), "resolution") : null;

        int structured = intOf(jdbc.queryForObject("""
                select count(*) from cdss_patient_allergy where patient_id = ? and status = 'ACTIVE'
                """, Integer.class, patientId));

        var unmapped = jdbc.queryForList("""
                select a.id, a.name from cdss_patient_allergy pa
                join cdss_allergen a on a.id = pa.allergen_id
                where pa.patient_id = ? and pa.status = 'ACTIVE'
                  and not exists (select 1 from cdss_allergen_drug m where m.allergen_id = pa.allergen_id)
                order by a.id
                """, patientId);

        var m = new LinkedHashMap<String, Object>();
        m.put("allergyHistoryText", raw);
        m.put("hasFreeText", hasText);
        m.put("unreviewedText", unreviewedText);
        m.put("latestTextResolution", latestResolution);
        m.put("structuredActiveCount", structured);
        m.put("unmappedAllergens", unmapped);
        // 只有「原文已核对（或本就没有原文）」且「全部生效过敏原都有映射」时，这次审查才配说「没发现禁忌」
        boolean trustworthy = !unreviewedText && unmapped.isEmpty() && !"UNCLEAR".equals(latestResolution);
        m.put("trustworthy", trustworthy);
        return m;
    }

    private List<String> coverageWarnings(Map<String, Object> coverage) {
        var out = new ArrayList<String>();
        if (Boolean.TRUE.equals(coverage.get("unreviewedText"))) {
            out.add("【过敏审查覆盖不全】该患者有自由文本过敏史尚未人工核对："
                    + trimTo(String.valueOf(coverage.get("allergyHistoryText")), 120)
                    + "——本次审查未覆盖其中任何内容，请人工阅读原文后判断");
        } else if ("UNCLEAR".equals(coverage.get("latestTextResolution"))) {
            out.add("【过敏审查覆盖不全】该患者的自由文本过敏史经人工核对结论为「无法判定」，"
                    + "尚未转成结构化过敏原——不等于无过敏，请人工阅读原文后判断");
        }
        @SuppressWarnings("unchecked")
        var unmapped = (List<Map<String, Object>>) coverage.get("unmappedAllergens");
        if (unmapped != null && !unmapped.isEmpty()) {
            out.add("【过敏审查覆盖不全】该患者有 " + unmapped.size() + " 个已确认过敏原尚未映射到任何院内药品（"
                    + unmapped.stream().map(u -> String.valueOf(u.get("name"))).toList()
                    + "），它们在本次审查中不可能命中，请药剂科补全映射");
        }
        return out;
    }

    /** 直接命中的提示文案：把「凭什么」说全，医生才判断得动，才不会养成无脑点继续的习惯 */
    private String directMessage(Map<String, Object> h, String gate) {
        return "【过敏%s】%s：患者对「%s」过敏（%s，%s%s确认%s）；本药%s。%s".formatted(
                "block".equals(gate) ? "禁忌" : "警告",
                h.get("drug_name"),
                h.get("allergen_name"),
                severityText(String.valueOf(h.get("severity"))),
                sourceText(String.valueOf(h.get("source"))),
                h.get("confirmed_by_name") == null ? "" : "，" + h.get("confirmed_by_name"),
                h.get("manifestation") == null ? "" : "，表现：" + h.get("manifestation"),
                levelText(String.valueOf(h.get("mapped_level"))),
                "block".equals(gate) ? "已拦截" : "如确需使用请复核过敏史并记录理由");
    }

    /** 交叉命中的提示文案：必须写明这是<b>交叉风险</b>而非确证过敏，否则医生无从区分轻重 */
    private String crossMessage(Map<String, Object> h) {
        return "【过敏交叉风险】%s：患者对「%s」过敏（%s，%s），本药与其存在交叉过敏风险（%s ↔ %s，风险 %s%s）。%s".formatted(
                h.get("drug_name"),
                h.get("allergen_name"),
                severityText(String.valueOf(h.get("severity"))),
                sourceText(String.valueOf(h.get("source"))),
                h.get("group_name"), h.get("cross_group_name"),
                h.get("risk_level"),
                h.get("cross_note") == null ? "" : "：" + h.get("cross_note"),
                "交叉过敏为概率性风险，本提示不拦截开单，请结合病史与皮试结果判断");
    }

    private Map<String, Object> hitBody(Map<String, Object> h, String hitType, String message) {
        var m = new LinkedHashMap<String, Object>();
        m.put("hitType", hitType);
        m.put("drugId", h.get("drug_id"));
        m.put("drugName", h.get("drug_name"));
        m.put("allergyId", h.get("allergy_id"));
        m.put("allergenId", h.get("allergen_id"));
        m.put("allergenName", h.get("allergen_name"));
        m.put("severity", h.get("severity"));
        m.put("manifestation", h.get("manifestation"));
        m.put("source", h.get("source"));
        m.put("confirmedAt", h.get("confirmed_at"));
        m.put("confirmedByName", h.get("confirmed_by_name"));
        m.put("mappedLevel", h.get("mapped_level"));
        if ("CROSS".equals(hitType)) {
            m.put("groupName", h.get("group_name"));
            m.put("crossGroupName", h.get("cross_group_name"));
            m.put("crossAllergenName", h.get("cross_allergen_name"));
            m.put("riskLevel", h.get("risk_level"));
            m.put("blocking", false);
        } else {
            m.put("blocking", true);
        }
        m.put("message", message);
        return m;
    }

    /**
     * 写 {@code cdss_alert}：既有 {@code GET /api/cdss/alerts} 因此不改一行代码就能看到过敏提醒。
     *
     * <p>{@code cdss_alert.registration_id} 是 not null，故只在有挂号号时写——
     * 这不是遗漏，是既有表的约束；没有挂号号的审查（住院侧接入、患者档案侧预检）
     * 仍然会写 {@code cdss_allergy_gate_log}，留痕不缺。
     * message 列 varchar(255)，超长按字符截断（截断的是给人看的文案，结构化明细在 gate 台账与返回体里）。
     */
    private void writeAlert(Long registrationId, String severity, Map<String, Object> hit) {
        jdbc.update("insert into cdss_alert(registration_id, rule_type, severity, message) values (?,?,?,?)",
                registrationId, "ALLERGY", severity, trimTo(String.valueOf(hit.get("message")), 255));
    }

    /**
     * 写过敏审查台账。
     *
     * <p><b>block 档单独走 REQUIRES_NEW</b>：拦截会向调用方抛 5612，
     * 而开单链路是事务性的——同一事务里写的台账会跟着业务回滚，
     * 于是<b>最该被记下来的那次（有人差点给过敏患者开了药）反而一条不留</b>。
     * 独立事务写入是唯一能留住它的办法。
     *
     * <p>独立事务失败时回落到当前事务写一遍并吞掉异常：
     * 在被 {@code @Transactional} 包住、患者行尚未提交的场景里（测试、以及同一事务内新建患者后
     * 立即开单），另一个连接看不见那一行，外键会挡下插入。台账写不进去<b>不该反过来打断临床动作</b>，
     * 但也不能因此让 block 档变成静默——回落一次是这个取舍的落点。
     */
    private void writeGateLog(Long patientId, Long registrationId, String gate, int drugCount,
                              int directHits, int crossHits, boolean blocked,
                              Map<String, Object> coverage, String detail, Long operatorId,
                              boolean requiresNewTx) {
        @SuppressWarnings("unchecked")
        var unmapped = (List<Map<String, Object>>) coverage.get("unmappedAllergens");
        Object[] args = {patientId, registrationId, gate, drugCount, directHits, crossHits, blocked,
                Boolean.TRUE.equals(coverage.get("unreviewedText")),
                unmapped == null ? 0 : unmapped.size(), trimTo(detail, 500), operatorId, nowTs()};
        String sql = """
                insert into cdss_allergy_gate_log(patient_id, registration_id, gate, drug_count,
                        direct_hits, cross_hits, blocked, unreviewed_text, unmapped_count,
                        detail, operator_id, occurred_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        if (!requiresNewTx) {
            jdbc.update(sql, args);
            return;
        }
        var tpl = new TransactionTemplate(transactionManager);
        tpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            tpl.executeWithoutResult(status -> jdbc.update(sql, args));
        } catch (RuntimeException e) {
            jdbc.update(sql, args);
        }
    }

    private String summarize(List<Map<String, Object>> direct, List<Map<String, Object>> cross) {
        var parts = new ArrayList<String>();
        direct.forEach(h -> parts.add(h.get("drugName") + "←" + h.get("allergenName")));
        cross.forEach(h -> parts.add(h.get("drugName") + "≈" + h.get("allergenName") + "(交叉)"));
        return parts.isEmpty() ? null : String.join("；", parts);
    }

    // ==================================================================
    // 二、患者过敏档
    // ==================================================================

    /**
     * 患者过敏档：结构化记录 + <b>自由文本原文</b> + 覆盖度。
     *
     * <p>原文与结构化记录<b>并排返回</b>，刻意不做「有结构化就不显示原文」的收敛：
     * 结构化只是原文的一次人工提取，提取得对不对、有没有漏，只有对着原文才看得出来。
     */
    public Map<String, Object> patientProfile(Long patientId) {
        var patient = patient(patientId);
        var body = new LinkedHashMap<String, Object>();
        body.put("patientId", patientId);
        body.put("patientName", patient.get("name"));
        body.put("allergyHistoryText", patient.get("allergy_history"));
        body.put("allergies", jdbc.queryForList("""
                select pa.id, pa.allergen_id, a.code as allergen_code, a.name as allergen_name,
                       a.allergen_type, a.drug_level, pa.severity, pa.manifestation, pa.source,
                       pa.status, pa.confirmed_at, pa.confirmed_by, cu.real_name as confirmed_by_name,
                       pa.revoked_at, ru.real_name as revoked_by_name, pa.revoke_reason,
                       pa.source_text, pa.note,
                       (select count(*) from cdss_allergen_drug m where m.allergen_id = pa.allergen_id)
                           as mapped_drug_count
                from cdss_patient_allergy pa
                join cdss_allergen a  on a.id = pa.allergen_id
                left join sys_user cu on cu.id = pa.confirmed_by
                left join sys_user ru on ru.id = pa.revoked_by
                where pa.patient_id = ?
                order by (pa.status = 'ACTIVE') desc, pa.id desc
                """, patientId));
        body.put("textReviews", jdbc.queryForList("""
                select r.id, r.source_text, r.resolution, r.created_count, r.note,
                       r.reviewed_at, r.reviewed_by, u.real_name as reviewed_by_name
                from cdss_allergy_text_review r
                left join sys_user u on u.id = r.reviewed_by
                where r.patient_id = ? order by r.reviewed_at desc, r.id desc
                """, patientId));
        body.put("coverage", coverage(patientId, patient.get("allergy_history")));
        body.put("note", "allergyHistoryText 是原文，本模块只读不写；allergies 是人工确认的结构化结果。"
                + "两者并排显示，便于核对提取是否有遗漏。mapped_drug_count=0 的过敏原永远不会命中开单审查");
        return body;
    }

    /**
     * 登记一条结构化过敏记录。
     *
     * <p><b>确认人不可空（5606）</b>：这条记录会拦处方，必须有人为它签字。
     * 允许匿名落库，等于允许任何一条脏数据无声地拦住临床，而事后找不到人问「依据是什么」。
     */
    @Transactional
    public Map<String, Object> addPatientAllergy(Long patientId, Long allergenId, String severity,
                                                 String manifestation, String source, String note,
                                                 String sourceText, Long operatorId) {
        patient(patientId);
        if (operatorId == null) throw new BizException(5606, "无法识别当前登录用户，不能登记过敏记录");
        var allergen = allergen(allergenId, true);
        String sev = upper(severity);
        if (!SEVERITIES.contains(sev)) {
            throw new BizException(5604, "严重程度只能为 MILD/MODERATE/SEVERE/UNKNOWN，收到：" + severity);
        }
        String src = upper(source);
        if (!SOURCES.contains(src)) {
            throw new BizException(5604, "来源只能为 SELF_REPORT/CLINICAL/TEST/TEXT_REVIEW/OTHER，收到：" + source);
        }
        if (manifestation != null && manifestation.length() > 200) {
            throw new BizException(5604, "过敏表现最长 200 字");
        }

        Long id;
        try {
            id = jdbc.queryForObject("""
                    insert into cdss_patient_allergy(patient_id, allergen_id, severity, manifestation,
                            source, confirmed_by, confirmed_at, status, source_text, note)
                    values (?,?,?,?,?,?,?,'ACTIVE',?,?) returning id
                    """, Long.class, patientId, allergenId, sev, manifestation, src, operatorId,
                    nowTs(), trimTo(sourceText, 512), trimTo(note, 255));
        } catch (DuplicateKeyException e) {
            throw new BizException(5605, "该患者已有「" + allergen.get("name") + "」的生效过敏记录，"
                    + "如需修改请先撤销原记录（撤销留人/时刻/原因）");
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("id", id);
        body.put("patientId", patientId);
        body.put("allergenName", allergen.get("name"));
        int mapped = intOf(jdbc.queryForObject(
                "select count(*) from cdss_allergen_drug where allergen_id = ?", Integer.class, allergenId));
        body.put("mappedDrugCount", mapped);
        if (mapped == 0) {
            body.put("warnings", List.of("该过敏原尚未映射到任何院内药品，登记后**不会**在开单时命中——"
                    + "请药剂科在 POST /api/cdss/allergy/allergens/" + allergenId + "/drugs 补全映射"));
        } else {
            body.put("warnings", List.of());
        }
        return body;
    }

    /**
     * 撤销一条过敏记录（例如后续皮试证实并非过敏）。
     *
     * <p><b>不提供物理删除</b>：删掉一条过敏记录直接放开了一次拦截，这个动作不该是无痕的。
     * 原因必填（5609）——「谁在什么依据下把这条过敏撤了」是事后唯一能追的线索。
     */
    @Transactional
    public Map<String, Object> revokePatientAllergy(Long patientId, Long allergyId,
                                                    String reason, Long operatorId) {
        if (operatorId == null) throw new BizException(5606, "无法识别当前登录用户，不能撤销过敏记录");
        if (reason == null || reason.isBlank()) throw new BizException(5609, "撤销过敏记录必须填写原因");
        var rows = jdbc.queryForList(
                "select id, status from cdss_patient_allergy where id = ? and patient_id = ?",
                allergyId, patientId);
        if (rows.isEmpty()) throw new BizException(5607, "过敏记录不存在或不属于该患者：" + allergyId);
        if (!"ACTIVE".equals(rows.get(0).get("status"))) {
            throw new BizException(5608, "该过敏记录已撤销，不能重复撤销：" + allergyId);
        }
        int n = jdbc.update("""
                update cdss_patient_allergy
                set status = 'REVOKED', revoked_by = ?, revoked_at = ?, revoke_reason = ?
                where id = ? and status = 'ACTIVE'
                """, operatorId, nowTs(), trimTo(reason, 255), allergyId);
        if (n == 0) throw new BizException(5608, "该过敏记录已被并发撤销：" + allergyId);
        var body = new LinkedHashMap<String, Object>();
        body.put("id", allergyId);
        body.put("status", "REVOKED");
        return body;
    }

    // ==================================================================
    // 三、自由文本迁移工作台（**人工**，绝不自动解析）
    // ==================================================================

    /**
     * 待人工核对的自由文本过敏史。
     *
     * <p>待办是<b>实时算出来</b>的，不预生成任务行：原文会被医生随时修订，
     * 预生成的任务一旦过期，「已处置」的标记就挂在旧原文上，
     * 让新写进去的过敏史看起来已经核对过了——那是一个静默的假阴性工厂。
     * 故判据是「不存在 source_text 与<b>当前</b> allergy_history 完全相同的处置记录」。
     *
     * <p><b>这里只列原文，不做任何解析、不给任何「建议过敏原」</b>。
     * 一个自动填好的下拉框会把人工确认变成人工点确定——
     * 「青霉素皮试阴性」被预填成「青霉素」，多数人不会改。
     */
    public Map<String, Object> migrationWorklist(Integer limit, boolean includeReviewed) {
        int cap = capOf(limit);
        String where = includeReviewed ? "" : """
                  and not exists (select 1 from cdss_allergy_text_review r
                                  where r.patient_id = p.id and r.source_text = p.allergy_history)
                """;
        var rows = jdbc.queryForList("""
                select p.id as patient_id, p.patient_no, p.name as patient_name, p.sex, p.birth_date,
                       p.allergy_history,
                       (select count(*) from cdss_patient_allergy pa
                        where pa.patient_id = p.id and pa.status = 'ACTIVE') as structured_count,
                       (select r.resolution from cdss_allergy_text_review r
                        where r.patient_id = p.id and r.source_text = p.allergy_history
                        order by r.reviewed_at desc, r.id desc limit 1) as latest_resolution
                from empi_patient p
                where p.allergy_history is not null and btrim(p.allergy_history) <> ''
                """ + where + """
                order by p.id
                limit ?
                """, cap + 1);
        boolean truncated = rows.size() > cap;
        var body = new LinkedHashMap<String, Object>();
        body.put("items", truncated ? rows.subList(0, cap) : rows);
        body.put("truncated", truncated);
        body.put("limit", cap);
        body.put("pendingTotal", jdbc.queryForObject("""
                select count(*) from empi_patient p
                where p.allergy_history is not null and btrim(p.allergy_history) <> ''
                  and not exists (select 1 from cdss_allergy_text_review r
                                  where r.patient_id = p.id and r.source_text = p.allergy_history)
                """, Integer.class));
        body.put("note", "本工作台**只展示原文**，不解析、不预填、不给建议过敏原。"
                + "「青霉素过敏」与「青霉素皮试阴性」文本相近而语义相反，"
                + "自动解析会造成反向拦截（该拦的不拦、不该拦的拦住），比不做更危险。"
                + "原文永远保留在 empi_patient.allergy_history，本模块只读不写");
        return body;
    }

    /**
     * 人工核对处置：把一段原文判成「有这些过敏原 / 没有过敏 / 说不准」，并按判断落结构化记录。
     *
     * <p><b>提交必须带原文快照 {@code sourceText}，且与当前 allergy_history 逐字一致（5613）</b>：
     * 医生在护士打开工作台之后修订了过敏史，是完全可能发生的；
     * 若不校验，护士按<b>旧原文</b>做出的判断会被记成对<b>新原文</b>的核对，
     * 新写进去的内容从此再也不会出现在待办里——一次静默的漏核对。
     *
     * <p><b>NO_ALLERGY 与 UNCLEAR 必须分开留痕</b>：前者是「看过了，确实没有」，
     * 后者是「看过了，还说不准」。混成一个「已处理」，UNCLEAR 的患者就会被当成已排除过敏，
     * 而他恰恰是最需要再问一句的那个人（{@link #coverage} 因此不把 UNCLEAR 算作可信）。
     */
    @Transactional
    public Map<String, Object> reviewText(Long patientId, String sourceText, String resolution,
                                          List<AllergyInput> allergens, String note, Long operatorId) {
        var patient = patient(patientId);
        if (operatorId == null) throw new BizException(5606, "无法识别当前登录用户，不能提交过敏史核对");
        String current = patient.get("allergy_history") == null ? null
                : String.valueOf(patient.get("allergy_history"));
        if (current == null || current.isBlank()) {
            throw new BizException(5611, "该患者自由文本过敏史为空，无需核对");
        }
        if (!current.equals(sourceText)) {
            throw new BizException(5613, "提交的原文快照与当前过敏史不一致（原文可能已被修订），"
                    + "请重新打开工作台按最新原文核对。当前原文：" + trimTo(current, 120));
        }
        String res = upper(resolution);
        if (!RESOLUTIONS.contains(res)) {
            throw new BizException(5610, "核对结论只能为 STRUCTURED/NO_ALLERGY/UNCLEAR，收到：" + resolution);
        }
        var list = allergens == null ? List.<AllergyInput>of() : allergens;
        if ("STRUCTURED".equals(res) && list.isEmpty()) {
            throw new BizException(5610, "结论为「已确认并登记结构化过敏原」时必须至少选择一个过敏原；"
                    + "若原文不构成过敏记录请选 NO_ALLERGY，若无法判定请选 UNCLEAR");
        }
        if (!"STRUCTURED".equals(res) && !list.isEmpty()) {
            throw new BizException(5610, "结论为 " + res + " 时不得同时登记过敏原——"
                    + "「没有过敏」与「登记了过敏原」是互相矛盾的两件事");
        }

        var created = new ArrayList<Map<String, Object>>();
        for (AllergyInput in : list) {
            // 逐条走同一个登记入口：校验、唯一性、签字人口径只有一份，不给工作台开后门
            created.add(addPatientAllergy(patientId, in.allergenId(),
                    in.severity() == null ? "UNKNOWN" : in.severity(),
                    in.manifestation(),
                    in.source() == null ? "TEXT_REVIEW" : in.source(),
                    in.note(), current, operatorId));
        }

        Long reviewId = jdbc.queryForObject("""
                insert into cdss_allergy_text_review(patient_id, source_text, resolution, created_count,
                        note, reviewed_by, reviewed_at)
                values (?,?,?,?,?,?,?) returning id
                """, Long.class, patientId, current, res, created.size(),
                trimTo(note, 500), operatorId, nowTs());

        var body = new LinkedHashMap<String, Object>();
        body.put("reviewId", reviewId);
        body.put("patientId", patientId);
        body.put("resolution", res);
        body.put("createdAllergies", created);
        body.put("sourceTextPreserved", true);
        body.put("coverage", coverage(patientId, current));
        body.put("note", "原文未被改写（empi_patient.allergy_history 本模块只读）；"
                + "本次核对以原文快照留痕，原文日后若被修订，该患者会自动重回待办");
        return body;
    }

    /** 工作台提交的一条过敏原选择（allergenId 之外全部可空，缺省 severity=UNKNOWN、source=TEXT_REVIEW） */
    public record AllergyInput(Long allergenId, String severity, String manifestation,
                               String source, String note) {}

    // ==================================================================
    // 四、字典维护（药剂科）
    // ==================================================================

    /** 规则总览 + 维护完成度。coverage 段是「何时能收紧到 block」的唯一数据依据 */
    public Map<String, Object> rulesOverview(Integer limit) {
        int cap = capOf(limit);
        var m = new LinkedHashMap<String, Object>();
        m.put("gate", gate());
        m.put("allergens", jdbc.queryForList("""
                select a.id, a.code, a.name, a.allergen_type, a.drug_level, a.enabled, a.remark,
                       (select count(*) from cdss_allergen_drug d where d.allergen_id = a.id) as mapped_drug_count,
                       (select count(*) from cdss_patient_allergy pa
                        where pa.allergen_id = a.id and pa.status = 'ACTIVE') as patient_count
                from cdss_allergen a order by a.id limit ?
                """, cap));
        m.put("groups", jdbc.queryForList("""
                select g.id, g.code, g.name, g.enabled, g.remark,
                       (select count(*) from cdss_allergen_group_member gm where gm.group_id = g.id) as member_count
                from cdss_allergen_group g order by g.id limit ?
                """, cap));
        m.put("crossRisks", jdbc.queryForList("""
                select x.id, x.group_id_lo, gl.name as group_lo_name, x.group_id_hi, gh.name as group_hi_name,
                       x.risk_level, x.note
                from cdss_allergen_cross x
                join cdss_allergen_group gl on gl.id = x.group_id_lo
                join cdss_allergen_group gh on gh.id = x.group_id_hi
                order by x.id limit ?
                """, cap));

        var cov = new LinkedHashMap<String, Object>();
        cov.put("allergenCount", jdbc.queryForObject("select count(*) from cdss_allergen", Integer.class));
        cov.put("allergenWithoutDrugMap", jdbc.queryForObject("""
                select count(*) from cdss_allergen a
                where not exists (select 1 from cdss_allergen_drug d where d.allergen_id = a.id)
                """, Integer.class));
        cov.put("drugMapCount", jdbc.queryForObject("select count(*) from cdss_allergen_drug", Integer.class));
        cov.put("mappedDrugCount", jdbc.queryForObject(
                "select count(distinct drug_id) from cdss_allergen_drug", Integer.class));
        cov.put("enabledDrugCount", jdbc.queryForObject(
                "select count(*) from md_drug where enabled is true", Integer.class));
        cov.put("patientsWithFreeText", jdbc.queryForObject("""
                select count(*) from empi_patient
                where allergy_history is not null and btrim(allergy_history) <> ''
                """, Integer.class));
        cov.put("patientsTextPending", jdbc.queryForObject("""
                select count(*) from empi_patient p
                where p.allergy_history is not null and btrim(p.allergy_history) <> ''
                  and not exists (select 1 from cdss_allergy_text_review r
                                  where r.patient_id = p.id and r.source_text = p.allergy_history)
                """, Integer.class));
        cov.put("patientsWithStructured", jdbc.queryForObject("""
                select count(distinct patient_id) from cdss_patient_allergy where status = 'ACTIVE'
                """, Integer.class));
        m.put("coverage", cov);
        m.put("note", "过敏原目录、药品映射、交叉族的**内容全部由药剂科维护**，本平台不预置任何药学判断。"
                + "V152 的两条示例行 enabled=false 且无映射，命中不了任何处方，上线前须替换或删除。"
                + "coverage 段是把 " + GATE_KEY + " 收紧到 block 的数据依据："
                + "映射覆盖与人工核对完成度都上来之前，block 只会表现为「系统随机拦人」");
        return m;
    }

    @Transactional
    public Map<String, Object> addAllergen(String code, String name, String allergenType,
                                           String drugLevel, String remark, Long operatorId) {
        if (code == null || code.isBlank()) throw new BizException(5601, "过敏原编码不能为空");
        if (name == null || name.isBlank()) throw new BizException(5601, "过敏原名称不能为空");
        String type = upper(allergenType);
        if (!ALLERGEN_TYPES.contains(type)) {
            throw new BizException(5601, "过敏原类型只能为 DRUG/FOOD/OTHER，收到：" + allergenType);
        }
        String level = drugLevel == null || drugLevel.isBlank() ? null : upper(drugLevel);
        if (level != null && !DRUG_LEVELS.contains(level)) {
            throw new BizException(5601, "过敏原药物粒度只能为 INGREDIENT/PRODUCT/CLASS，收到：" + drugLevel);
        }
        Long id;
        try {
            id = jdbc.queryForObject("""
                    insert into cdss_allergen(code, name, allergen_type, drug_level, remark, created_by)
                    values (?,?,?,?,?,?) returning id
                    """, Long.class, code.trim(), name.trim(), type, level, trimTo(remark, 255), operatorId);
        } catch (DuplicateKeyException e) {
            throw new BizException(5601, "过敏原编码已存在：" + code);
        }
        return Map.of("id", id, "code", code.trim(), "name", name.trim());
    }

    /**
     * 启用/停用过敏原。
     *
     * <p><b>停用只挡新登记，不影响既有患者记录参与审查</b>——见 V152 注释：
     * 让一次字典整理静默关掉一批已确认的过敏拦截，是本版明确拒绝的行为。
     * 要让某条过敏不再拦人，正确动作是撤销那条<b>患者</b>记录（留人/时刻/原因），
     * 而不是从字典里把过敏原关掉。
     */
    @Transactional
    public Map<String, Object> setAllergenEnabled(Long allergenId, Boolean enabled) {
        if (enabled == null) throw new BizException(5601, "enabled 不能为空");
        allergen(allergenId, false);
        jdbc.update("update cdss_allergen set enabled = ? where id = ?", enabled, allergenId);
        int active = intOf(jdbc.queryForObject("""
                select count(*) from cdss_patient_allergy where allergen_id = ? and status = 'ACTIVE'
                """, Integer.class, allergenId));
        var body = new LinkedHashMap<String, Object>();
        body.put("id", allergenId);
        body.put("enabled", enabled);
        body.put("activePatientAllergyCount", active);
        body.put("note", "停用仅阻止新登记；已有 " + active + " 条生效患者记录**照常参与开单审查**。"
                + "要让某条过敏不再拦人，请撤销该患者的过敏记录（留人/时刻/原因），而不是停用字典条目");
        return body;
    }

    /** 过敏原 → 院内药品映射登记（三级断言之一）。**这是全模块唯一的命中依据** */
    @Transactional
    public Map<String, Object> mapDrug(Long allergenId, Long drugId, String mappedLevel,
                                       String note, Long operatorId) {
        allergen(allergenId, false);
        if (drugId == null) throw new BizException(5602, "药品 id 不能为空");
        var drug = jdbc.queryForList("select id, name from md_drug where id = ?", drugId);
        if (drug.isEmpty()) throw new BizException(5602, "药品不存在：" + drugId);
        String level = upper(mappedLevel);
        if (!DRUG_LEVELS.contains(level)) {
            throw new BizException(5602, "映射级别只能为 INGREDIENT/PRODUCT/CLASS，收到：" + mappedLevel);
        }
        Long id;
        try {
            id = jdbc.queryForObject("""
                    insert into cdss_allergen_drug(allergen_id, drug_id, mapped_level, note, created_by)
                    values (?,?,?,?,?) returning id
                    """, Long.class, allergenId, drugId, level, trimTo(note, 200), operatorId);
        } catch (DuplicateKeyException e) {
            throw new BizException(5602, "该过敏原与该药品的映射已存在（allergenId=" + allergenId
                    + ", drugId=" + drugId + "）");
        }
        return Map.of("id", id, "allergenId", allergenId, "drugId", drugId,
                "drugName", String.valueOf(drug.get(0).get("name")), "mappedLevel", level);
    }

    @Transactional
    public Map<String, Object> unmapDrug(Long mapId) {
        int n = jdbc.update("delete from cdss_allergen_drug where id = ?", mapId);
        if (n == 0) throw new BizException(5602, "映射不存在：" + mapId);
        return Map.of("id", mapId, "deleted", true);
    }

    public Map<String, Object> allergenDrugs(Long allergenId, Integer limit) {
        allergen(allergenId, false);
        int cap = capOf(limit);
        var body = new LinkedHashMap<String, Object>();
        body.put("allergenId", allergenId);
        body.put("items", jdbc.queryForList("""
                select m.id, m.drug_id, d.name as drug_name, d.spec, m.mapped_level, m.note, m.created_at
                from cdss_allergen_drug m join md_drug d on d.id = m.drug_id
                where m.allergen_id = ? order by m.id limit ?
                """, allergenId, cap));
        return body;
    }

    @Transactional
    public Map<String, Object> addGroup(String code, String name, String remark, Long operatorId) {
        if (code == null || code.isBlank()) throw new BizException(5603, "交叉过敏族编码不能为空");
        if (name == null || name.isBlank()) throw new BizException(5603, "交叉过敏族名称不能为空");
        Long id;
        try {
            id = jdbc.queryForObject("""
                    insert into cdss_allergen_group(code, name, remark, created_by)
                    values (?,?,?,?) returning id
                    """, Long.class, code.trim(), name.trim(), trimTo(remark, 255), operatorId);
        } catch (DuplicateKeyException e) {
            throw new BizException(5603, "交叉过敏族编码已存在：" + code);
        }
        return Map.of("id", id, "code", code.trim(), "name", name.trim());
    }

    @Transactional
    public Map<String, Object> addGroupMember(Long groupId, Long allergenId, Long operatorId) {
        group(groupId);
        allergen(allergenId, false);
        Long id;
        try {
            id = jdbc.queryForObject("""
                    insert into cdss_allergen_group_member(group_id, allergen_id, created_by)
                    values (?,?,?) returning id
                    """, Long.class, groupId, allergenId, operatorId);
        } catch (DuplicateKeyException e) {
            throw new BizException(5603, "该过敏原已在此族中（groupId=" + groupId + ", allergenId=" + allergenId + "）");
        }
        return Map.of("id", id, "groupId", groupId, "allergenId", allergenId);
    }

    @Transactional
    public Map<String, Object> removeGroupMember(Long memberId) {
        int n = jdbc.update("delete from cdss_allergen_group_member where id = ?", memberId);
        if (n == 0) throw new BizException(5603, "族成员不存在：" + memberId);
        return Map.of("id", memberId, "deleted", true);
    }

    /** 登记一对族间交叉风险（无序对，A-B 与 B-A 是同一条） */
    @Transactional
    public Map<String, Object> addCross(Long groupIdA, Long groupIdB, String riskLevel,
                                        String note, Long operatorId) {
        if (groupIdA == null || groupIdB == null) throw new BizException(5603, "两个族 id 都必须指定");
        if (groupIdA.equals(groupIdB)) throw new BizException(5603, "交叉风险必须登记在两个不同的族之间");
        group(groupIdA);
        group(groupIdB);
        String level = upper(riskLevel);
        if (!RISK_LEVELS.contains(level)) {
            throw new BizException(5603, "交叉风险级别只能为 HIGH/MEDIUM/LOW，收到：" + riskLevel);
        }
        long lo = Math.min(groupIdA, groupIdB), hi = Math.max(groupIdA, groupIdB);
        Long id;
        try {
            id = jdbc.queryForObject("""
                    insert into cdss_allergen_cross(group_id_lo, group_id_hi, risk_level, note, created_by)
                    values (?,?,?,?,?) returning id
                    """, Long.class, lo, hi, level, trimTo(note, 255), operatorId);
        } catch (DuplicateKeyException e) {
            throw new BizException(5603, "这两个族的交叉风险已登记（无序对，A-B 与 B-A 是同一条）");
        }
        return Map.of("id", id, "groupIdLo", lo, "groupIdHi", hi, "riskLevel", level);
    }

    /**
     * 撤销一条族间交叉风险。
     *
     * <p>删除是交叉规则唯一的关闭方式（族的 enabled 位只管新成员登记）：
     * 一条配得太宽的交叉规则会让大量合理处方吃到警告，而噪音正是让真警告失效的主要原因，
     * 所以它必须能被干净利落地撤掉，而不是靠停用一个族去间接压制。
     */
    @Transactional
    public Map<String, Object> removeCross(Long crossId) {
        int n = jdbc.update("delete from cdss_allergen_cross where id = ?", crossId);
        if (n == 0) throw new BizException(5603, "交叉风险不存在：" + crossId);
        return Map.of("id", crossId, "deleted", true);
    }

    /** 过敏审查台账（含未命中的审查，否则命中率的分母是假的） */
    public Map<String, Object> gateLog(Long patientId, Integer limit) {
        int cap = capOf(limit);
        var rows = patientId == null
                ? jdbc.queryForList("""
                        select g.*, p.name as patient_name, u.real_name as operator_name
                        from cdss_allergy_gate_log g
                        join empi_patient p on p.id = g.patient_id
                        left join sys_user u on u.id = g.operator_id
                        order by g.id desc limit ?
                        """, cap)
                : jdbc.queryForList("""
                        select g.*, p.name as patient_name, u.real_name as operator_name
                        from cdss_allergy_gate_log g
                        join empi_patient p on p.id = g.patient_id
                        left join sys_user u on u.id = g.operator_id
                        where g.patient_id = ? order by g.id desc limit ?
                        """, patientId, cap);
        var body = new LinkedHashMap<String, Object>();
        body.put("items", rows);
        body.put("limit", cap);
        body.put("note", "三档都记、命中与否都记。unreviewed_text=true 的行表示当次审查时"
                + "该患者仍有未人工核对的自由文本过敏史——「没拦住」与「没什么可拦」由这一列区分");
        return body;
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private Map<String, Object> patient(Long patientId) {
        if (patientId == null) throw new BizException(5600, "患者 id 不能为空");
        var rows = jdbc.queryForList(
                "select id, name, allergy_history from empi_patient where id = ?", patientId);
        if (rows.isEmpty()) throw new BizException(5600, "患者不存在：" + patientId);
        return rows.get(0);
    }

    private Map<String, Object> allergen(Long allergenId, boolean requireEnabled) {
        if (allergenId == null) throw new BizException(5601, "过敏原 id 不能为空");
        var rows = jdbc.queryForList("select id, name, enabled from cdss_allergen where id = ?", allergenId);
        if (rows.isEmpty()) throw new BizException(5601, "过敏原不存在：" + allergenId);
        if (requireEnabled && !Boolean.TRUE.equals(rows.get(0).get("enabled"))) {
            throw new BizException(5601, "过敏原已停用，不能新登记：" + rows.get(0).get("name"));
        }
        return rows.get(0);
    }

    private Map<String, Object> group(Long groupId) {
        if (groupId == null) throw new BizException(5603, "交叉过敏族 id 不能为空");
        var rows = jdbc.queryForList("select id, name from cdss_allergen_group where id = ?", groupId);
        if (rows.isEmpty()) throw new BizException(5603, "交叉过敏族不存在：" + groupId);
        return rows.get(0);
    }

    /** 去重保序后的药品 id 清单；空或超上限直接返 5614，不静默截断 */
    private List<Long> normalizeDrugIds(List<Long> drugIds) {
        if (drugIds == null || drugIds.isEmpty()) throw new BizException(5614, "送审药品清单不能为空");
        var ids = new ArrayList<Long>(new LinkedHashSet<>(drugIds.stream().filter(java.util.Objects::nonNull).toList()));
        if (ids.isEmpty()) throw new BizException(5614, "送审药品清单不能全为空值");
        if (ids.size() > MAX_DRUGS) {
            throw new BizException(5614, "单次送审药品数超上限 " + MAX_DRUGS + "（收到 " + ids.size()
                    + "）。此处不静默截断——截断意味着后面的药根本没审，却报了「已审」");
        }
        return ids;
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private static int capOf(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    private static int intOf(Integer v) {
        return v == null ? 0 : v;
    }

    private static String firstString(List<Map<String, Object>> rows, String col) {
        return rows.isEmpty() || rows.get(0).get(col) == null ? null : String.valueOf(rows.get(0).get(col));
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static String trimTo(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String severityText(String severity) {
        return switch (severity) {
            case "MILD" -> "轻度";
            case "MODERATE" -> "中度";
            case "SEVERE" -> "重度";
            default -> "严重程度不详";
        };
    }

    /** 来源必须出现在提示里：自述与皮试证实的可信度差一个量级，医生要靠它决定这条提示有多重 */
    private static String sourceText(String source) {
        return switch (source) {
            case "SELF_REPORT" -> "患者自述";
            case "CLINICAL" -> "临床记录";
            case "TEST" -> "皮试/激发试验证实";
            case "TEXT_REVIEW" -> "由过敏史原文人工核对";
            default -> "来源其他";
        };
    }

    /** 映射级别决定提示怎么说：「即该药本身」与「同为该类」在临床上完全不是一回事 */
    private static String levelText(String level) {
        return switch (level) {
            case "PRODUCT" -> "即该过敏原对应药品本身";
            case "INGREDIENT" -> "含该过敏原成分";
            case "CLASS" -> "与该过敏原同属一个药理类别";
            default -> "与该过敏原存在院内维护的对应关系";
        };
    }
}
