package cn.hip.outpatient.service;

import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.service.ConfigReader;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * v51 车道 D ①：给药途径与溶媒配伍校验（错误码 5660–5679）。
 *
 * <h2>先说数据源：md_drug 没有给药途径字段</h2>
 * 逐条读过 {@code md_drug} 自 {@code V4__masterdata.sql:3} 建表起的全部 alter
 * （V22 abx_level / V32 drug_class+ddd / V116 self_pay / V132 fee_category /
 * V134 停用留痕 / V149 high_alert / V150 pack_size+min_unit）与
 * {@code DrugItem.java} 的每一个字段：<b>没有任何一列表示给药途径</b>。
 * 最接近的是 {@code dose_form}（剂型：片剂/胶囊/口服液/注射剂），但那是「药做成什么样」，
 * 不是「怎么给进去」——同一支注射剂可静滴、可肌注，也可能严禁静推，剂型推不出途径。
 *
 * <p>故本服务的第一件事不是写规则，而是<b>把缺失的数据源补成可维护结构</b>：
 * {@code cdss_route_dict}（途径受控词表）、{@code cdss_drug_route}（药品适用途径，一药多行）、
 * {@code cdss_solvent_dict}（哪些品规是溶媒）、{@code cdss_solvent_rule}（配伍规则）。
 * <b>四张表的内容一律由药剂科维护，本版一条药学结论都不内置</b>——
 * 编出来的配伍禁忌看起来很专业，医生会信，然后出事。V155 迁移零回填，
 * 只在两张<b>词表</b>各留一条 {@code is_example} 示例行示范结构。
 *
 * <h2>自由文本不做脚本解析（本车道的等价物是 usage_route）</h2>
 * 医嘱的途径落在 {@code outp_order.usage_route varchar(32)}，是<b>自由文本</b>。
 * 本类<b>不写正则、不写关键词包含匹配</b>，只做两步：
 * <ol>
 *   <li>{@link #normalizeRouteText} 归一化——<b>纯字符级</b>：去掉全部空白（含 U+3000）、
 *       ASCII 转小写。不去标点、不做同义替换、不做全角半角折叠；</li>
 *   <li>拿归一化结果去 {@code cdss_route_alias.alias_text} 做<b>等值查找</b>。</li>
 * </ol>
 * 查不到就是<b>查不到</b>：不判定、不拦截，只回一条「该用法文本未登记」的提示并留痕，
 * 让药剂科去补别名。理由与车道 A 的过敏史一致——「静滴」（静脉滴注）与「静推」（静脉注射）
 * 是两条不同途径，而 contains/正则在这组词上一定会错，错的方向要么放行该拦的、
 * 要么拦住不该拦的，两个都比不做更危险。
 *
 * <h2>「未维护」永远不等于「禁止」</h2>
 * 三处都按这条走，因为它决定了「表只录了一半时会发生什么」：
 * <ul>
 *   <li>药品没维护适用途径 → 不判定（不是「任何途径都不许」）；</li>
 *   <li>该药没有任何溶媒规则 → 不判定；</li>
 *   <li>该药有规则但本次溶媒不在其中 → 不判定（<b>不在白名单 ≠ 禁配</b>）。</li>
 * </ul>
 * 把空白当结论，等于用「我们还没维护完」去制造「这条医嘱有问题」。宁可漏报。
 *
 * <h2>gate</h2>
 * {@link #GATE_ROUTE} / {@link #GATE_SOLVENT} 三态 off|warn|block，<b>默认 warn、坏值回落 warn</b>
 * （不回落 off——宁可多提示，不可静默失效）。warn 档<b>真的把提示给到医生</b>：
 * 返回体 {@code warnings} 数组 + {@code cdss_route_alert} 留痕，两者都有，缺一不可。
 * <b>「用法文本未登记」在任何档位下都不拦</b>，包括 block——看不懂的文本上不做拦截。
 *
 * <h2>既有链路一字未动</h2>
 * {@link CdssService} 的三类规则（DDI / 疗程 / 年龄）与 {@code CdssController} 的 4 个端点
 * 逐字保留，错误码 4015 / 4017 / 4650 原样。本类是<b>新路径</b>
 * （{@code /api/cdss/route/**}），留痕落自己的 {@code cdss_route_alert}，
 * 不混进既有 {@code cdss_alert}（混表会改掉既有提醒报表的口径，同 V153 判断）。
 * 接进开单校验是<b>只增不改</b>的一次调用，本车道不改 {@code DoctorStationService}，
 * 需求已写进 cross_lane；本版另提供只读回顾端点，使功能在不改既有链路的前提下也能真的跑。
 *
 * <h2>错误码 5660–5679</h2>
 * 实测只用掉 4 个：5660 请求参数非法、5661 途径不符（block 档）、
 * 5662 溶媒配伍禁忌（block 档）、5663 规则维护参数非法。
 * 5664–5679 空置且<b>不预留登记</b>（不写代码就不占码，沿用 v48/v50 口径）。
 */
@Service
@RequiredArgsConstructor
public class RouteRuleService {

    // ===================== 错误码（5660–5679，见 docs/错误码分段.md） =====================

    /** 请求参数非法：药品不存在/已停用、待审查行为空、挂号不存在、分页越界 */
    public static final int E_PARAM       = 5660;
    /** 给药途径与药品适用途径不符（仅 block 档抛出） */
    public static final int E_ROUTE       = 5661;
    /** 溶媒配伍禁忌（仅 block 档抛出） */
    public static final int E_SOLVENT     = 5662;
    /** 规则维护参数非法：途径编码不存在、verdict 取值非法、依据留空、重复登记 */
    public static final int E_RULE_INPUT  = 5663;

    // ===================== 配置键 =====================

    public static final String GATE_ROUTE   = "cdss.gate.route";
    public static final String GATE_SOLVENT = "cdss.gate.solvent";

    /** 单次审查行数上限：开单一次几十行封顶，1000 足够且能挡住误传整表 */
    public static final int MAX_LINES = 1000;

    // ===================== 提示分类（kind） =====================

    /** 途径与药品适用途径不符——本类<b>唯一</b>会因途径而拦截的情形 */
    public static final String K_ROUTE_MISMATCH     = "ROUTE_MISMATCH";
    /** 用法文本未登记在别名表——<b>任何档位都不拦</b> */
    public static final String K_ROUTE_UNRECOGNIZED = "ROUTE_UNRECOGNIZED";
    /** 医嘱未填用法途径 */
    public static final String K_ROUTE_MISSING      = "ROUTE_MISSING";
    /** 该药未维护适用途径（数据源缺口的直接表现） */
    public static final String K_ROUTE_UNCONFIGURED = "ROUTE_UNCONFIGURED";
    /** 溶媒配伍禁忌 */
    public static final String K_SOLVENT_FORBID     = "SOLVENT_FORBID";
    /** 静脉途径但同组未见已登记的溶媒 */
    public static final String K_SOLVENT_MISSING    = "SOLVENT_MISSING";
    /** 该药一条溶媒规则都没有 */
    public static final String K_SOLVENT_UNCONFIGURED = "SOLVENT_UNCONFIGURED";
    /** 该药有溶媒规则，但本次所用溶媒不在其中——<b>不在白名单 ≠ 禁配</b>，不拦 */
    public static final String K_SOLVENT_UNLISTED   = "SOLVENT_UNLISTED";

    private final JdbcTemplate jdbc;
    private final ConfigReader configReader;

    // ===================== 对外数据形态 =====================

    /**
     * 待审查的一条用药医嘱行。
     *
     * @param drugId     md_drug.id
     * @param usageRoute 用法途径**原始自由文本**（直接来自 outp_order.usage_route，不预处理）
     * @param groupNo    处方组号：同组的溶媒行与主药行据此配对。为空视为独立一组
     * @param orderId    已落库医嘱行 id（回顾性审查时有值，开单前审查为 null），仅用于留痕
     */
    public record OrderLine(Long drugId, String usageRoute, String groupNo, Long orderId) {
        public OrderLine(Long drugId, String usageRoute, String groupNo) {
            this(drugId, usageRoute, groupNo, null);
        }
    }

    /** 一条判定结果。severity：VIOLATION 违规（gate 决定是否拦）/ NOTE 仅提示（永不拦） */
    public record Finding(String kind, String severity, Long drugId, String drugName,
                          String routeTextRaw, String routeCode, String solventCode,
                          Long orderId, String message) {}

    /**
     * 审查结果。
     *
     * <p>{@code blocked} 为 true 表示按当前 gate 档位应当拒绝开单；
     * {@link #check} 会在此时抛 {@link HipBizException}，只读的 {@link #review} 不抛。
     */
    public record CheckResult(String routeGate, String solventGate, boolean blocked,
                              int lineCount, List<Finding> violations, List<String> warnings,
                              List<String> notes, Map<String, Object> coverage,
                              List<String> caveats) {}

    // ===================== 归一化：纯字符级，不做语义变换 =====================

    /**
     * 用法途径文本归一化。<b>写入别名表与查询别名表走同一个函数</b>，
     * 故 {@code cdss_route_alias.alias_text} 上的 unique 约束是有效的去重。
     *
     * <p>只做两件事：去掉全部空白字符（含 U+3000 全角空格）、ASCII 字母转小写。
     * <b>刻意不做</b>：去标点（「静滴·q8h」与「静滴」不该被判成同一个词）、
     * 同义替换（那就是语义解析）、全角半角折叠（会让形近词意外相等）。
     *
     * @return 归一化文本；入参为 null 或归一化后为空串时返回 null（视为「未填」）
     */
    public static String normalizeRouteText(String raw) {
        if (raw == null) return null;
        String t = raw.replaceAll("[\\s\\u3000]+", "");
        if (t.isEmpty()) return null;
        return t.toLowerCase(Locale.ROOT);
    }

    // ===================== gate =====================

    /** 当前 gate 档位；未知/坏值回落 warn（不是 off——宁可多提示，不可静默失效） */
    public String gate(String key) {
        String v = configReader.get(key, "warn");
        return switch (v == null ? "" : v.trim().toLowerCase(Locale.ROOT)) {
            case "off" -> "off";
            case "block" -> "block";
            default -> "warn";
        };
    }

    // ===================== 审查主流程 =====================

    /**
     * 开单前审查（<b>会留痕；block 档抛异常</b>）。这是接进 {@code DoctorStationService.createOrders}
     * 的接缝：接法是在既有 {@code cdssService.checkPrescription(...)} 之后<b>增加一次调用</b>，
     * 既有那一行与 4015/4017 逐字不动——只增不改。
     *
     * <p><b>刻意不加 {@code @Transactional}</b>：本方法先写留痕、后抛拦截异常。若自带事务，
     * 抛出的那一刻会把刚写的留痕一起回滚，block 档就变成「拦了但查不到拦过谁」。
     * 不加注解时，独立调用（本车道的 REST 端点）走 JdbcTemplate 自动提交，留痕落得住。
     *
     * <p><b>已知局限，如实标注</b>：将来接进 {@code createOrders} 那种外层事务里时，
     * 拦截异常仍会让外层回滚，留痕随之消失——这与既有 {@link CdssService} 的 CAUTION 留痕
     * 在 FORBID 抛出时一并回滚是同一种行为，本版不单独造一条 REQUIRES_NEW 审计通道
     * （那要动事务传播口径，须单独评估）。block 档的完整明细写在异常消息里，医生当场看得见。
     * 需求已写进 cross_lane。
     */
    public CheckResult check(Long registrationId, Long patientId, List<OrderLine> lines, Long operatorId) {
        CheckResult r = evaluate(lines);
        persistAlerts(registrationId, patientId, operatorId, r);
        if (r.blocked()) {
            List<Finding> blocking = r.violations().stream()
                    .filter(f -> isBlockingKind(f.kind()))
                    .toList();
            boolean solvent = blocking.stream().anyMatch(f -> K_SOLVENT_FORBID.equals(f.kind()));
            int code = solvent ? E_SOLVENT : E_ROUTE;
            String head = solvent ? "CDSS 拦截【溶媒配伍】" : "CDSS 拦截【给药途径】";
            throw new HipBizException(code, head + "："
                    + String.join("；", blocking.stream().map(Finding::message).toList()));
        }
        return r;
    }

    /** 只读审查：不留痕、不抛、不改任何数据。前端「开单前预检」与药剂科回顾核对共用 */
    public CheckResult review(List<OrderLine> lines) {
        return evaluate(lines);
    }

    /**
     * 对某次挂号已开的药品医嘱做<b>只读回顾核对</b>。
     *
     * <p>本版不改 {@code DoctorStationService}（不在本车道名下），若只留一个需要别人来接的
     * 接缝，本条需求在本版就是「建了表、写了引擎、一次也没跑过」。此端点让引擎在
     * <b>不动既有开单链路</b>的前提下跑在真实数据上：读 {@code outp_order} 的
     * {@code item_id / usage_route / group_no}，走同一套判定，只是不写、不拦。
     */
    public CheckResult reviewRegistration(Long registrationId) {
        if (registrationId == null) throw new HipBizException(E_PARAM, "挂号 id 必填");
        Integer n = jdbc.queryForObject("select count(*) from outp_registration where id = ?",
                Integer.class, registrationId);
        if (n == null || n == 0) throw new HipBizException(E_PARAM, "挂号不存在");
        List<OrderLine> lines = jdbc.queryForList("""
                select id, item_id, usage_route, group_no
                  from outp_order
                 where registration_id = ? and order_type = 'DRUG' and status <> 'CANCELLED'
                 order by id
                """, registrationId).stream()
                .map(r -> new OrderLine(((Number) r.get("item_id")).longValue(),
                        (String) r.get("usage_route"), (String) r.get("group_no"),
                        ((Number) r.get("id")).longValue()))
                .toList();
        if (lines.isEmpty()) {
            return new CheckResult(gate(GATE_ROUTE), gate(GATE_SOLVENT), false, 0,
                    List.of(), List.of(), List.of("该挂号下没有未取消的药品医嘱"), coverage(), caveats());
        }
        return evaluate(lines);
    }

    /**
     * 纯判定：不读配置以外的可变状态、不写任何表、不抛业务拦截异常。
     *
     * <p>判定顺序刻意是「先途径、后溶媒」，且溶媒只对 {@code intravenous = true} 的途径跑——
     * 口服药谈溶媒没有意义，把溶媒检查铺到所有途径上只会制造噪音，噪音多了整类提示会被无视。
     */
    public CheckResult evaluate(List<OrderLine> lines) {
        if (lines == null || lines.isEmpty()) throw new HipBizException(E_PARAM, "待审查医嘱行为空");
        if (lines.size() > MAX_LINES) {
            throw new HipBizException(E_PARAM, "单次审查行数超上限 " + MAX_LINES);
        }
        String routeGate = gate(GATE_ROUTE);
        String solventGate = gate(GATE_SOLVENT);

        // ---- 判定所需字典逐药只查一次（逐行查会把 N 行放大成 4N 次往返，N 上限 1000）----
        Map<Long, String> drugNames = new HashMap<>();
        Map<Long, List<String>> allowedByDrug = new HashMap<>();
        Map<Long, Map<String, Object>> solventByDrug = new HashMap<>();
        Map<Long, List<Map<String, Object>>> solventRulesByDrug = new HashMap<>();
        for (OrderLine l : lines) {
            if (l == null || l.drugId() == null) throw new HipBizException(E_PARAM, "医嘱行缺少药品 id");
            Long id = l.drugId();
            if (drugNames.containsKey(id)) continue;
            drugNames.put(id, drugName(id));                     // 药品不存在即在此报 5660
            allowedByDrug.put(id, allowedRoutes(id));
            solventByDrug.put(id, solventOf(id));                // null = 该品规不是溶媒
            solventRulesByDrug.put(id, solventRules(id));
        }
        Map<String, Boolean> ivByRoute = new HashMap<>();
        Map<String, String> nameByRoute = new HashMap<>();
        for (var r : jdbc.queryForList("select route_code, route_name, intravenous from cdss_route_dict where enabled")) {
            ivByRoute.put((String) r.get("route_code"), (Boolean) r.get("intravenous"));
            nameByRoute.put((String) r.get("route_code"), (String) r.get("route_name"));
        }

        List<Finding> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        // ---- 同组溶媒预扫描：先把「这一组里有哪几行是已登记的溶媒」找出来 ----
        Map<String, List<SolventLine>> solventsByGroup = new LinkedHashMap<>();
        for (OrderLine l : lines) {
            Map<String, Object> s = solventByDrug.get(l.drugId());
            if (s == null) continue;
            solventsByGroup.computeIfAbsent(groupKey(l), k -> new ArrayList<>())
                    .add(new SolventLine(l.drugId(), (String) s.get("solvent_code"),
                            (String) s.get("solvent_name")));
        }

        for (OrderLine l : lines) {
            String drugName = drugNames.get(l.drugId());
            String raw = l.usageRoute();
            String norm = normalizeRouteText(raw);

            // ================= 途径 =================
            // 文本→编码的**解析**与途径**判定**是两件事，故解析不受 routeGate 影响：
            // 溶媒校验要靠 routeCode 才知道这一行是不是静脉给药，
            // 若把解析也关掉，关闭途径 gate 会连带把溶媒 gate 一起悄悄关掉——
            // 那是一个用户看不见的耦合，比多写三行代码危险得多。
            String routeCode = norm == null ? null : aliasToRoute(norm);
            if (!"off".equals(routeGate)) {
                if (norm == null) {
                    notes.add(note(K_ROUTE_MISSING, drugName, "未填写用法途径，本行不作途径判定"));
                } else if (routeCode == null) {
                    // **任何档位都不拦**（含 block）：看不懂的文本上做拦截比不做更危险。
                    // severity=NOTE，不参与 blocked 判定；仍进 violations 供前端逐条展示、并留痕，
                    // 因为「哪些写法没登记」只能靠真实流量发现，药剂科据此补别名
                    Finding f = new Finding(K_ROUTE_UNRECOGNIZED, "NOTE", l.drugId(), drugName,
                            raw, null, null, l.orderId(),
                            "【途径未识别】%s 的用法「%s」未登记在途径别名表，本次不作判定（不猜、不拦）。"
                                    .formatted(drugName, raw));
                    notes.add(f.message());
                    violations.add(f);
                } else {
                    List<String> allowed = allowedByDrug.get(l.drugId());
                    if (allowed.isEmpty()) {
                        notes.add(note(K_ROUTE_UNCONFIGURED, drugName,
                                "尚未维护适用给药途径（md_drug 无途径字段，数据源缺口），本行不作途径判定"));
                    } else if (!allowed.contains(routeCode)) {
                        String msg = "【途径不符】%s 本次用法「%s」（%s），"
                                .formatted(drugName, raw, nameByRoute.getOrDefault(routeCode, routeCode))
                                + "药剂科维护的适用途径为 %s".formatted(routeNames(allowed, nameByRoute));
                        Finding f = new Finding(K_ROUTE_MISMATCH, "VIOLATION", l.drugId(), drugName,
                                raw, routeCode, null, l.orderId(), msg);
                        violations.add(f);
                        if (!"block".equals(routeGate)) warnings.add(msg);
                    }
                }
            }

            // ================= 溶媒（仅静脉途径）=================
            if ("off".equals(solventGate) || routeCode == null) continue;
            if (!Boolean.TRUE.equals(ivByRoute.get(routeCode))) continue;
            if (solventByDrug.get(l.drugId()) != null) continue;   // 这一行自己就是溶媒，不对自己查配伍

            List<SolventLine> groupSolvents = solventsByGroup.getOrDefault(groupKey(l), List.of());
            if (groupSolvents.isEmpty()) {
                notes.add(note(K_SOLVENT_MISSING, drugName,
                        "为静脉途径但同组未见已登记的溶媒行，本行不作溶媒判定"));
                continue;
            }
            List<Map<String, Object>> rules = solventRulesByDrug.get(l.drugId());
            if (rules.isEmpty()) {
                notes.add(note(K_SOLVENT_UNCONFIGURED, drugName, "尚未维护任何溶媒配伍规则，本行不作溶媒判定"));
                continue;
            }
            for (SolventLine sv : groupSolvents) {
                Map<String, Object> hit = rules.stream()
                        .filter(r -> sv.solventCode().equals(r.get("solvent_code")))
                        .findFirst().orElse(null);
                if (hit == null) {
                    // **不在白名单 ≠ 禁配**：拿一张录到一半的表当禁配依据，
                    // 等于把「我们还没维护完」伪装成「这条医嘱有问题」
                    notes.add(note(K_SOLVENT_UNLISTED, drugName,
                            "与溶媒「%s」的配伍未登记，本次不作判定（未登记不等于禁配）".formatted(sv.solventName())));
                    continue;
                }
                if (!"FORBID".equals(hit.get("verdict"))) continue;
                String msg = "【溶媒配伍】%s + %s（%s）：%s〔依据 %s / %s〕".formatted(
                        drugName, sv.solventName(), sv.solventCode(),
                        hit.get("message"), hit.get("basis_source"), hit.get("basis_level"));
                Finding f = new Finding(K_SOLVENT_FORBID, "VIOLATION", l.drugId(), drugName,
                        raw, routeCode, sv.solventCode(), l.orderId(), msg);
                violations.add(f);
                if (!"block".equals(solventGate)) warnings.add(msg);
            }
        }

        boolean blocked = violations.stream().anyMatch(f ->
                (K_ROUTE_MISMATCH.equals(f.kind()) && "block".equals(routeGate))
                        || (K_SOLVENT_FORBID.equals(f.kind()) && "block".equals(solventGate)));
        return new CheckResult(routeGate, solventGate, blocked, lines.size(),
                List.copyOf(violations), List.copyOf(warnings), List.copyOf(notes),
                coverage(), caveats());
    }

    private record SolventLine(Long drugId, String solventCode, String solventName) {}

    private boolean isBlockingKind(String kind) {
        return K_ROUTE_MISMATCH.equals(kind) || K_SOLVENT_FORBID.equals(kind);
    }

    private static String groupKey(OrderLine l) {
        return l.groupNo() == null || l.groupNo().isBlank() ? "__NO_GROUP__" + l.drugId() : l.groupNo();
    }

    private String note(String kind, String drugName, String text) {
        return "[" + kind + "] " + drugName + "：" + text;
    }

    private static String routeNames(List<String> codes, Map<String, String> nameByRoute) {
        return String.join("、", codes.stream().map(c -> nameByRoute.getOrDefault(c, c) + "(" + c + ")").toList());
    }

    // ===================== 留痕 =====================

    /**
     * 命中留痕。<b>只落三类真正需要药剂科去处理的</b>：途径不符、溶媒禁配、用法文本未识别。
     *
     * <p>「未维护适用途径 / 未维护溶媒规则」<b>刻意不落表</b>：那不是某一张处方的问题，
     * 而是全局的维护进度，每开一次方就写一行只会把这张表淹掉。它由
     * {@link #coverage()} 按「已维护药品数 / 全部启用药品数」如实报数，一个数说清楚。
     */
    private void persistAlerts(Long registrationId, Long patientId, Long operatorId, CheckResult r) {
        Timestamp now = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
        for (Finding f : r.violations()) {
            boolean route = K_ROUTE_MISMATCH.equals(f.kind());
            boolean solvent = K_SOLVENT_FORBID.equals(f.kind());
            boolean unrecognized = K_ROUTE_UNRECOGNIZED.equals(f.kind());
            if (!route && !solvent && !unrecognized) continue;
            String gate = solvent ? r.solventGate() : r.routeGate();
            // 未识别在任何档位都不拦，故 blocked 恒 false，不跟 gate 走
            boolean blocked = !unrecognized && "block".equals(gate);
            jdbc.update("""
                    insert into cdss_route_alert(registration_id, patient_id, order_id, drug_id, kind,
                                                 gate, blocked, route_text_raw, route_text_norm,
                                                 route_code, solvent_code, message, operator_id, created_at)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, registrationId, patientId, f.orderId(), f.drugId(), f.kind(),
                    gate, blocked, trim(f.routeTextRaw(), 64), trim(normalizeRouteText(f.routeTextRaw()), 64),
                    f.routeCode(), f.solventCode(), trim(f.message(), 512), operatorId, now);
        }
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ===================== 字典读取 =====================

    private String drugName(Long drugId) {
        var rows = jdbc.queryForList("select name, enabled from md_drug where id = ?", drugId);
        if (rows.isEmpty()) throw new HipBizException(E_PARAM, "药品不存在：id=" + drugId);
        return (String) rows.get(0).get("name");
    }

    private String aliasToRoute(String normalized) {
        var rows = jdbc.queryForList("""
                select a.route_code from cdss_route_alias a
                  join cdss_route_dict d on d.route_code = a.route_code and d.enabled
                 where a.alias_text = ?
                """, normalized);
        return rows.isEmpty() ? null : (String) rows.get(0).get("route_code");
    }

    private List<String> allowedRoutes(Long drugId) {
        return jdbc.queryForList("""
                select r.route_code from cdss_drug_route r
                  join cdss_route_dict d on d.route_code = r.route_code and d.enabled
                 where r.drug_id = ? and r.enabled
                 order by r.route_code
                """, String.class, drugId);
    }

    private Map<String, Object> solventOf(Long drugId) {
        var rows = jdbc.queryForList(
                "select solvent_code, solvent_name from cdss_solvent_dict where drug_id = ? and enabled", drugId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String, Object>> solventRules(Long drugId) {
        return jdbc.queryForList("""
                select solvent_code, verdict, message, basis_source, basis_level
                  from cdss_solvent_rule where drug_id = ? and enabled
                """, drugId);
    }

    // ===================== 规则维护（药剂科） =====================

    /** 登记/停启用一条给药途径。route_code 由院内定义，本方法不校验它「是不是一条真实途径」 */
    @Transactional
    public Map<String, Object> addRoute(String routeCode, String routeName, boolean intravenous,
                                        String remark, Long operatorId) {
        String code = requireText(routeCode, 24, "途径编码");
        String name = requireText(routeName, 64, "途径名称");
        if (exists("select 1 from cdss_route_dict where route_code = ?", code)) {
            throw new HipBizException(E_RULE_INPUT, "途径编码已存在：" + code);
        }
        jdbc.update("""
                insert into cdss_route_dict(route_code, route_name, intravenous, is_example, enabled,
                                            remark, created_by, created_at)
                values (?,?,?,false,true,?,?,?)
                """, code, name, intravenous, trim(remark, 255), operatorId, nowTs());
        return Map.of("routeCode", code, "routeName", name, "intravenous", intravenous);
    }

    /**
     * 登记一条用法文本别名。<b>入库前走同一个 {@link #normalizeRouteText}</b>——
     * 若维护端不归一化而查询端归一化，药剂科录的「静脉滴注 」（带尾空格）会永远匹配不上，
     * 而界面上看着两个词一模一样，谁都查不出来。
     */
    @Transactional
    public Map<String, Object> addAlias(String aliasText, String routeCode, Long operatorId) {
        String norm = normalizeRouteText(aliasText);
        if (norm == null) throw new HipBizException(E_RULE_INPUT, "别名文本不能为空");
        if (norm.length() > 64) throw new HipBizException(E_RULE_INPUT, "别名文本归一化后超 64 字符");
        String code = requireText(routeCode, 24, "途径编码");
        if (!exists("select 1 from cdss_route_dict where route_code = ?", code)) {
            throw new HipBizException(E_RULE_INPUT, "途径编码不存在，请先在词表登记：" + code);
        }
        if (exists("select 1 from cdss_route_alias where alias_text = ?", norm)) {
            throw new HipBizException(E_RULE_INPUT, "该用法文本已登记（归一化后为「" + norm + "」）");
        }
        jdbc.update("""
                insert into cdss_route_alias(alias_text, route_code, is_example, created_by, created_at)
                values (?,?,false,?,?)
                """, norm, code, operatorId, nowTs());
        return Map.of("aliasText", norm, "routeCode", code,
                "note", "引擎按归一化文本做**等值**查找，不做正则/包含匹配；未登记的写法不会被识别，也不会被拦截");
    }

    /** 维护药品适用给药途径。依据必填——没有出处的规则不许入库（DB 侧 ck_cdss_drug_route_basis 兜底） */
    @Transactional
    public Map<String, Object> addDrugRoute(Long drugId, String routeCode, String basisSource, Long operatorId) {
        String name = drugName(drugId);
        String code = requireText(routeCode, 24, "途径编码");
        String basis = requireText(basisSource, 255, "依据出处（说明书版本/院内用药目录/指南名称+年份）");
        if (!exists("select 1 from cdss_route_dict where route_code = ? and enabled", code)) {
            throw new HipBizException(E_RULE_INPUT, "途径编码不存在或已停用：" + code);
        }
        if (exists("select 1 from cdss_drug_route where drug_id = ? and route_code = ?", drugId, code)) {
            throw new HipBizException(E_RULE_INPUT, "该药该途径已登记");
        }
        jdbc.update("""
                insert into cdss_drug_route(drug_id, route_code, basis_source, enabled, created_by, created_at)
                values (?,?,?,true,?,?)
                """, drugId, code, basis, operatorId, nowTs());
        return Map.of("drugId", drugId, "drugName", name, "routeCode", code);
    }

    /** 登记一个溶媒品规。刻意逐品规登记而非按药名判——见 V155 迁移注释 */
    @Transactional
    public Map<String, Object> addSolventDrug(Long drugId, String solventCode, String solventName,
                                              String basisSource, Long operatorId) {
        String name = drugName(drugId);
        String code = requireText(solventCode, 24, "溶媒分组编码");
        String sName = requireText(solventName, 64, "溶媒名称");
        String basis = requireText(basisSource, 255, "依据出处");
        if (exists("select 1 from cdss_solvent_dict where drug_id = ?", drugId)) {
            throw new HipBizException(E_RULE_INPUT, "该品规已登记为溶媒");
        }
        jdbc.update("""
                insert into cdss_solvent_dict(drug_id, solvent_code, solvent_name, basis_source,
                                              enabled, created_by, created_at)
                values (?,?,?,?,true,?,?)
                """, drugId, code, sName, basis, operatorId, nowTs());
        return Map.of("drugId", drugId, "drugName", name, "solventCode", code, "solventName", sName);
    }

    /**
     * 维护一条溶媒配伍规则。
     *
     * <p>{@code solventCode} 必须已在 {@code cdss_solvent_dict} 登记过——否则写错一个字母的规则
     * 会安静地永远匹配不上，而维护页上它看起来是「已经配好了」。
     */
    @Transactional
    public Map<String, Object> addSolventRule(Long drugId, String solventCode, String verdict,
                                              String basisSource, String basisLevel, String message,
                                              Long operatorId) {
        String name = drugName(drugId);
        String code = requireText(solventCode, 24, "溶媒分组编码");
        String v = verdict == null ? "" : verdict.trim().toUpperCase(Locale.ROOT);
        if (!"ALLOW".equals(v) && !"FORBID".equals(v)) {
            throw new HipBizException(E_RULE_INPUT, "verdict 只能为 ALLOW / FORBID");
        }
        if (!exists("select 1 from cdss_solvent_dict where solvent_code = ? and enabled", code)) {
            throw new HipBizException(E_RULE_INPUT,
                    "溶媒分组编码未登记：" + code + "。请先在溶媒目录登记至少一个该编码下的品规，"
                            + "否则本规则永远不会被匹配到，而维护页上它看起来是已配好的");
        }
        String basis = requireText(basisSource, 255, "依据出处");
        String level = requireText(basisLevel, 64, "依据级别");
        String msg = requireText(message, 512, "提示内容");
        if (exists("select 1 from cdss_solvent_rule where drug_id = ? and solvent_code = ?", drugId, code)) {
            throw new HipBizException(E_RULE_INPUT, "该药与该溶媒的规则已存在");
        }
        jdbc.update("""
                insert into cdss_solvent_rule(drug_id, solvent_code, verdict, basis_source, basis_level,
                                              message, enabled, created_by, created_at)
                values (?,?,?,?,?,?,true,?,?)
                """, drugId, code, v, basis, level, msg, operatorId, nowTs());
        return Map.of("drugId", drugId, "drugName", name, "solventCode", code, "verdict", v);
    }

    // ===================== 查询 =====================

    /** 规则库总览（药剂科维护页数据源） */
    public Map<String, Object> rules() {
        var m = new LinkedHashMap<String, Object>();
        m.put("routes", jdbc.queryForList(
                "select * from cdss_route_dict order by is_example desc, route_code"));
        m.put("aliases", jdbc.queryForList("""
                select a.*, d.route_name from cdss_route_alias a
                  join cdss_route_dict d on d.route_code = a.route_code
                 order by a.is_example desc, a.alias_text
                """));
        m.put("drugRoutes", jdbc.queryForList("""
                select r.*, g.name as drug_name, d.route_name from cdss_drug_route r
                  join md_drug g on g.id = r.drug_id
                  join cdss_route_dict d on d.route_code = r.route_code
                 order by g.name, r.route_code
                """));
        m.put("solventDrugs", jdbc.queryForList("""
                select s.*, g.name as drug_name from cdss_solvent_dict s
                  join md_drug g on g.id = s.drug_id order by s.solvent_code, g.name
                """));
        m.put("solventRules", jdbc.queryForList("""
                select r.*, g.name as drug_name from cdss_solvent_rule r
                  join md_drug g on g.id = r.drug_id order by g.name, r.solvent_code
                """));
        m.put("coverage", coverage());
        m.put("caveats", caveats());
        return m;
    }

    /** 提示留痕（药剂科据此补别名、复核拦截） */
    public List<Map<String, Object>> alerts(String kind, int limit) {
        int size = Math.max(1, Math.min(limit, 500));
        if (kind == null || kind.isBlank()) {
            return jdbc.queryForList("""
                    select a.*, g.name as drug_name from cdss_route_alert a
                      join md_drug g on g.id = a.drug_id order by a.id desc limit ?
                    """, size);
        }
        return jdbc.queryForList("""
                select a.*, g.name as drug_name from cdss_route_alert a
                  join md_drug g on g.id = a.drug_id where a.kind = ? order by a.id desc limit ?
                """, kind.trim().toUpperCase(Locale.ROOT), size);
    }

    /**
     * 维护覆盖率（v46/v48 立的诚实标注三件套之一）。
     *
     * <p>没有这一段，「途径校验已上线」会被读成「开错途径系统会拦」，
     * 而它今天的真实状态很可能是「0 个药维护了适用途径，因而一次也不会判定」。
     */
    public Map<String, Object> coverage() {
        var m = new LinkedHashMap<String, Object>();
        int enabledDrugs = count("select count(*) from md_drug where enabled");
        int routeTotal = count("select count(*) from cdss_route_dict");
        int routeExample = count("select count(*) from cdss_route_dict where is_example");
        int aliasTotal = count("select count(*) from cdss_route_alias");
        int aliasExample = count("select count(*) from cdss_route_alias where is_example");
        int drugsWithRoute = count("""
                select count(distinct r.drug_id) from cdss_drug_route r
                  join md_drug g on g.id = r.drug_id where r.enabled and g.enabled
                """);
        int solventDrugs = count("select count(*) from cdss_solvent_dict where enabled");
        int drugsWithSolventRule = count("select count(distinct drug_id) from cdss_solvent_rule where enabled");

        m.put("enabledDrugs", enabledDrugs);
        m.put("routeDictTotal", routeTotal);
        m.put("routeDictExampleRows", routeExample);
        m.put("routeDictMaintainedRows", routeTotal - routeExample);
        m.put("routeAliasTotal", aliasTotal);
        m.put("routeAliasExampleRows", aliasExample);
        m.put("routeAliasMaintainedRows", aliasTotal - aliasExample);
        m.put("drugsWithRouteConfigured", drugsWithRoute);
        m.put("routeCoverageRatePct", enabledDrugs == 0 ? 0.0
                : Math.round(drugsWithRoute * 10000.0 / enabledDrugs) / 100.0);
        m.put("rateFormula", "routeCoverageRatePct = drugsWithRouteConfigured / enabledDrugs × 100");
        m.put("solventDrugsRegistered", solventDrugs);
        m.put("drugsWithSolventRule", drugsWithSolventRule);
        m.put("routeCheckActive", drugsWithRoute > 0);
        m.put("solventCheckActive", solventDrugs > 0 && drugsWithSolventRule > 0);
        m.put("note", "drugsWithRouteConfigured = 0 时**途径校验不会产生任何判定**——"
                + "规则表是空的，不是「全院途径都对」。溶媒同理。"
                + "V155 零回填：md_drug 无给药途径字段，本版不按 dose_form 推断（注射剂里"
                + "可静滴/仅肌注/禁静推三类都有，猜错就是拦错），全部内容由药剂科维护。");
        return m;
    }

    /** 当前 gate 与匹配口径（前端据此决定是否显示必填星号，避免两边口径各写一份） */
    public Map<String, Object> settings() {
        var m = new LinkedHashMap<String, Object>();
        m.put("routeGate", gate(GATE_ROUTE));
        m.put("solventGate", gate(GATE_SOLVENT));
        m.put("gateKeys", List.of(GATE_ROUTE, GATE_SOLVENT));
        m.put("matching", "usage_route 自由文本 →(去全部空白+ASCII小写)→ cdss_route_alias 等值查找。"
                + "**不做正则/关键词/包含匹配**；查不到即不判定、不拦截。");
        m.put("note", "gate 三态 off|warn|block，默认 warn、坏值回落 warn（不回落 off）。"
                + "warn 档不返错误码，问题以返回体 warnings 回带并落 cdss_route_alert。"
                + "「用法文本未识别」在**任何档位**下都不拦。");
        return m;
    }

    private List<String> caveats() {
        return List.of(
                "md_drug **没有给药途径字段**（实测 V4 建表 + V22/V32/V116/V132/V134/V149/V150 全部 alter）。"
                        + "dose_form 是剂型不是途径，不可互推。cdss_drug_route 是该缺口的可维护替代，建完即空。",
                "usage_route 是自由文本，本引擎只做**等值**匹配。「静滴」与「静推」是两条不同途径，"
                        + "任何 contains/正则都会在这组词上出错，故未登记的写法一律不判定、不拦截。",
                "**未维护 ≠ 禁止**：药品未配途径、该药无溶媒规则、该溶媒未登记，三种情形一律不判定。"
                        + "拿录到一半的表当禁配依据，是把维护缺口伪装成临床问题。",
                "本版**不内置任何药学知识**：适用途径与配伍规则全部由药剂科按院内用药目录维护，"
                        + "且 basis_source / basis_level 非空白由数据库约束兜底，没有出处的规则入不了库。");
    }

    // ===================== 内部工具 =====================

    private String requireText(String s, int max, String what) {
        if (s == null || s.isBlank()) throw new HipBizException(E_RULE_INPUT, what + "必填");
        String t = s.trim();
        if (t.length() > max) throw new HipBizException(E_RULE_INPUT, what + "超长（上限 " + max + " 字符）");
        return t;
    }

    private boolean exists(String sql, Object... args) {
        return !jdbc.queryForList(sql, args).isEmpty();
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    /** 写入前截断到微秒：交由 default now() 会拿到微秒以下精度，回读后与写入值不等（本仓已因此炸过四次） */
    private static Timestamp nowTs() {
        return Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /** 供测试与前端展示的常量集合（避免两边各写一份 kind 字面量） */
    public static Set<String> allKinds() {
        return new LinkedHashSet<>(List.of(K_ROUTE_MISMATCH, K_ROUTE_UNRECOGNIZED, K_ROUTE_MISSING,
                K_ROUTE_UNCONFIGURED, K_SOLVENT_FORBID, K_SOLVENT_MISSING,
                K_SOLVENT_UNCONFIGURED, K_SOLVENT_UNLISTED));
    }
}
