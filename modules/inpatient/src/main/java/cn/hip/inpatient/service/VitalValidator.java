package cn.hip.inpatient.service;

import cn.hip.inpatient.entity.InpVitalSign;
import cn.hip.platform.core.service.ConfigReader;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * v47 写入校验收口：{@code POST /api/inpatient/admissions/{id}/vitals} 的服务端校验。
 *
 * <p><b>为什么必须有</b>：inp_vital_sign 自 V9 建表以来写路径<b>零服务端校验</b>——
 * {@code @RequestBody InpVitalSign} 后 {@code setId(null)} 直接 save，量程只活在前端
 * {@code frontend/shell/src/utils/vitals.ts}。v42 之前这只是脏数据；<b>v42 之后体温单已经能
 * 打印出纸</b>，API 直调写进去的 999℃ 会直接上法定护理文书且事后无法追溯。
 *
 * <p><b>gate 三态（{@value #GATE_KEY}，默认 warn）</b>：
 * <ul>
 *   <li>{@code off}——整段跳过（连入院时间那次查询都不发），返回体与 v46 逐字节相同；</li>
 *   <li>{@code warn}（默认）——<b>不拦截</b>，照常落库，但把问题以 warnings 回带，
 *       让护士当场看见「体温 999 已记录，请核对」，而不是静默存进去；</li>
 *   <li>{@code block}——返回错误码拒绝落库。</li>
 * </ul>
 * <b>默认 warn 而非 block</b>：本平台此前从无此校验，直接 block 会让存量对接方与移动端
 * 瞬间大面积失败（历史补录、时钟偏差、只录出入量的行都会撞上）。warn 先让问题可见，
 * 收紧节奏由院方按自家数据质量决定。坏配置回落 warn 而不是 off——宁可多提示，不可静默失效。
 *
 * <p><b>量程唯一事实源</b>：见 {@link #RANGES}。
 */
@Service
@RequiredArgsConstructor
public class VitalValidator {

    /** 三态 gate 键（V143 seed = warn），同时登记在 docs/配置手册.md */
    public static final String GATE_KEY = "emr.gate.vital.range";

    /**
     * 允许的时钟超前容差（秒）。PDA / 床旁监护仪与服务器的时钟偏差是常态，
     * 零容差会让分秒之差的正常录入直接写不进去。
     */
    public static final long CLOCK_SKEW_TOLERANCE_SECONDS = 300;

    /** 体温测量部位白名单（与 V129 列注释、前端 InpNurseView 的 MEASURE_SITES 同码） */
    public static final Set<String> MEASURE_SITES = Set.of("ORAL", "AXILLARY", "RECTAL");

    /**
     * 体征量程（六项体征 + 降温后体温）。
     *
     * <p><b>量程唯一事实源（v42 立的纪律，v47 继续遵守）</b>：这里的数字与
     * {@code frontend/shell/src/utils/vitals.ts:17-24} 的 {@code VITAL_RANGES} <b>同源一份</b>——
     * 1.2.5 v23-B 时桌面护士站与移动端各写一套阈值（体温 34–43 vs 30–45、脉搏 220 vs 250），
     * 同一个患者在两端录入得到不同的拦截结果。本版<b>不开第三套数字</b>：
     * <b>两边任一处改动必须同步改另一处</b>，否则服务端与前端的拦截结果又会分叉。
     *
     * <p>取值仍是两端并集中较宽者（临床极端值确实存在：低温治疗 30℃、室上速 250 次/分），
     * 闭区间，两端边界值合法。
     */
    private static final List<Range> RANGES = List.of(
            new Range("体温(℃)", "30", "45"),
            new Range("脉搏", "20", "250"),
            new Range("呼吸", "5", "60"),
            new Range("收缩压", "40", "300"),
            new Range("舒张压", "20", "200"),
            new Range("血氧", "50", "100"),
            // **降温后体温**：与体温同区间。它不是"第七项体征"，而是物理降温后的复测值，
            // 但它同样**印在三测单上**（TempSheetSvg 画降温后体温的下降箭头），
            // 写进 999 一样会上法定护理文书——凡是能出纸的字段都得拦。
            // **仍是同源不是第三套**：前端 InpNurseView.vue:31-32 这个输入框本就绑
            // VITAL_RANGES.temperature（复用体温区间），此处与之逐字一致。
            new Range("降温后体温(℃)", "30", "45"));

    private record Range(String label, BigDecimal min, BigDecimal max) {
        Range(String label, String min, String max) {
            this(label, new BigDecimal(min), new BigDecimal(max));
        }

        /** 闭区间，两端合法 */
        boolean contains(BigDecimal v) {
            return v.compareTo(min) >= 0 && v.compareTo(max) <= 0;
        }

        String describe(BigDecimal v) {
            return label + " 超出合理范围（" + min.toPlainString() + "–" + max.toPlainString()
                    + "）：" + v.toPlainString();
        }
    }

    /** 一条校验不通过项：错误码 + 面向护士的可读消息 */
    public record Violation(int code, String message) {}

    private final ConfigReader configReader;
    private final JdbcTemplate jdbc;

    /** 当前 gate 档位；未知值回落 warn（不是 off——宁可多提示，不可静默失效） */
    public String gate() {
        String v = configReader.get(GATE_KEY, "warn");
        return switch (v == null ? "" : v.trim().toLowerCase()) {
            case "off" -> "off";
            case "block" -> "block";
            default -> "warn";
        };
    }

    /**
     * 校验一条待写入的体征记录，返回全部不通过项（按 4822 → 4824 → 4825 → 4823 的固定顺序）。
     * gate=off 时直接返回空列表，<b>连入院时间那次查询都不发</b>。
     *
     * @param admissionId 路径上的住院 id（时序校验的比对基准从这里查）
     */
    public List<Violation> validate(Long admissionId, InpVitalSign v) {
        if ("off".equals(gate())) return List.of();

        List<Violation> out = new ArrayList<>();

        // ① 4822 测量部位白名单（v42 预留码，本版启用）。不传合法——大量记录本就不含体温。
        String site = v.getMeasureSite();
        if (site != null && !site.isBlank() && !MEASURE_SITES.contains(site.trim())) {
            out.add(new Violation(4822,
                    "体温测量部位非法（ORAL 口温 / AXILLARY 腋温 / RECTAL 肛温）：" + site));
        }

        // ② 4824 量程（六项体征 + 降温后体温）
        List<BigDecimal> values = values(v);
        for (int i = 0; i < RANGES.size(); i++) {
            BigDecimal x = values.get(i);
            if (x != null && !RANGES.get(i).contains(x)) {
                out.add(new Violation(4824, RANGES.get(i).describe(x)));
            }
        }

        // ③ 4825 时序
        out.addAll(timeViolations(admissionId, v.getMeasuredAt()));

        // ④ 4823 **整行全空**时未测原因必填——三测单要画「未测」而不是把曲线断开，
        //    没有原因就画不出（TempSheetSvg 的「未测」标记正是靠 not_measured_reason 驱动）。
        //
        //    **口径为什么不是"六项体征全空"**：护理实操里存在大量**只录出入量/体重/大便**
        //    的行——夜班的液体平衡记录就是典型（V42TempSheetTest 的 d1b 正是这样一条）。
        //    按"六项全空"判，这些**本来就不该测体征**的正常记录会被判成漏测；warn 档只是
        //    多一条无谓提示，但一旦院方切到 block，夜班的液体平衡记录就**直接写不进去**。
        //    所以口径收窄为「连出入量/体重/大便都没有」——即这一行**什么都没记**才要原因。
        boolean nothingMeasured = values.stream().allMatch(java.util.Objects::isNull)
                && v.getIntakeMl() == null && v.getOutputMl() == null
                && v.getStoolCount() == null && v.getWeightKg() == null;
        boolean noReason = v.getNotMeasuredReason() == null || v.getNotMeasuredReason().isBlank();
        if (nothingMeasured && noReason) {
            out.add(new Violation(4823, "本次记录未填写任何体征、出入量、体重或大便，必须填写未测原因"
                    + "（外出/拒测/手术中等），否则三测单画不出「未测」标记"));
        }
        return out;
    }

    /** 按 {@link #RANGES} 的顺序取值（统一成 BigDecimal 好与量程比）——**两个列表必须同序等长** */
    private List<BigDecimal> values(InpVitalSign v) {
        return java.util.Arrays.asList(
                v.getTemperature(),
                v.getPulse() == null ? null : BigDecimal.valueOf(v.getPulse()),
                v.getRespiration() == null ? null : BigDecimal.valueOf(v.getRespiration()),
                v.getSbp() == null ? null : BigDecimal.valueOf(v.getSbp()),
                v.getDbp() == null ? null : BigDecimal.valueOf(v.getDbp()),
                v.getSpo2() == null ? null : BigDecimal.valueOf(v.getSpo2()),
                v.getTempAfterCooling());
    }

    /**
     * 时序校验：不得晚于当前时刻（留 {@value #CLOCK_SKEW_TOLERANCE_SECONDS} 秒时钟容差）、
     * 不得早于入院时间、已出院的不得补录到出院之后。
     *
     * <p>住院记录查不到时<b>整段跳过</b>而不另报错——既有 POST /vitals 对不存在的 admissionId
     * 本就交由数据库外键去拒，这里不改那条既有行为。
     */
    private List<Violation> timeViolations(Long admissionId, Instant measuredAt) {
        if (measuredAt == null) return List.of();   // 控制器随后补 now()，天然合法

        Instant now = Instant.now();
        if (measuredAt.isAfter(now.plusSeconds(CLOCK_SKEW_TOLERANCE_SECONDS))) {
            return List.of(new Violation(4825, "测量时间不得晚于当前时刻：" + measuredAt));
        }
        if (admissionId == null) return List.of();

        var rows = jdbc.queryForList(
                "select admit_at, discharged_at from inp_admission where id = ?", admissionId);
        if (rows.isEmpty()) return List.of();

        List<Violation> out = new ArrayList<>();
        Instant admitAt = instant(rows.get(0).get("admit_at"));
        Instant dischargedAt = instant(rows.get(0).get("discharged_at"));
        if (admitAt != null && measuredAt.isBefore(admitAt)) {
            out.add(new Violation(4825, "测量时间不得早于入院时间（" + admitAt + "）：" + measuredAt));
        }
        if (dischargedAt != null && measuredAt.isAfter(dischargedAt)) {
            out.add(new Violation(4825, "患者已出院，不得把体征补录到出院时间（"
                    + dischargedAt + "）之后：" + measuredAt));
        }
        return out;
    }

    private Instant instant(Object o) {
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (o instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (o instanceof Instant i) return i;
        return null;
    }
}
