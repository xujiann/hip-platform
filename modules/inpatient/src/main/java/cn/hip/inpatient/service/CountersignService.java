package cn.hip.inpatient.service;

import cn.hip.platform.core.service.ConfigReader;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * v53 车道 V2：上级医师审签。
 *
 * <p><b>本类补的是「审签这个动作本身」——谁签、什么时候签、签的是哪一版。</b>
 * V115（v34）的 {@code superior_correction} 已经记录了「上级说了什么」，本类不重建那套、
 * 一列都不碰：那是一段写在被审记录自己身上的自由文本，没有签名人、没有签名时刻、
 * 也不绑定正文版本，且只长在 {@code record_type='ROUND'} 的行上。
 *
 * <h3>三条判据（本版的验收标准是「能不能证明」，不是「好不好用」）</h3>
 * <ol>
 *   <li><b>审签人不得与书写人为同一人（5722）。这条与 gate 无关，永远生效。</b>
 *       gate 管的是「未审签能不能出院归档」，不管「一个人能不能同时占书写位与审签位」。
 *       书写人未知（历史病历 {@code doctor_id} 为空）时同样返 5722——无从核验就不能盖章放行。
 *       与 v48 病理双签 5263、v51 同口径。</li>
 *   <li><b>审签绑定到具体版本。</b>落库的 {@code content_sha256} 是审签当时那份正文的摘要，
 *       重算当前正文即可判定「签完又改了」（stale）。版本号 {@code version_id/version_no}
 *       是对 v53 车道 V1 版本表的<b>软引用</b>（配置寻址、无外键、取不到就落 DIGEST_ONLY），
 *       <b>摘要在任何情况下都成立</b>——本类不因 V1 未落地而不可用。</li>
 *   <li><b>只增不改不删。</b>没有「撤销审签」——撤销等于把签过的字擦掉。
 *       需要否定前一次审签时，路径是「病历被改 → 旧审签自动 stale → 重新审签」。</li>
 * </ol>
 *
 * <h3>本类不做的事</h3>
 * <ul>
 *   <li><b>不判定「谁有资格当上级」</b>：本仓七个角色里没有住院医/主治/主任的分级，
 *       {@code sys_user.title} 是自由文本。从文本里正则出资格与 v51 从自由文本过敏史解析
 *       过敏原是同一类错误，两个方向的误判都致命。只把审签当时的职称原文照抄进快照列供举证。</li>
 *   <li><b>不改任何既有写路径</b>：{@link #verdict(Long)} 是<b>纯只读、不 throw</b> 的判定，
 *       挂到出院/归档哪个挡点由主控裁决（已写进 cross_lane）。本车道不碰
 *       {@code DoctorStationService} / {@code InpatientService} / {@code InpEmrController}。</li>
 *   <li><b>不提供回滚</b>：同本版总纪律，法定病历只回看不回滚。</li>
 * </ul>
 *
 * <h3>错误码（子段 5720–5739，本版实测用掉 7 个：5720–5726，5727–5739 空置）</h3>
 * <ul>
 *   <li>5720 病历不存在</li>
 *   <li>5721 无法识别当前登录用户，不能审签</li>
 *   <li>5722 审签人不得与书写人为同一人（含书写人未知、无从核验）</li>
 *   <li>5723 该病历当前正文您已审签过，不可重复审签（并发撞唯一键同码）</li>
 *   <li>5724 审签意见超长</li>
 *   <li>5725 未完成上级审签不得出院/归档（<b>只有 block 档才返</b>，warn 档改走 warnings）</li>
 *   <li>5726 待审签工作列表入参非法</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CountersignService {

    /** gate 配置键（V158 已随地基写入，默认值 warn） */
    public static final String GATE_KEY = "emr.gate.countersign";
    private static final String REQUIRED_TYPES_KEY = "emr.countersign.required_types";
    private static final String DUE_HOURS_KEY = "emr.countersign.due_hours";
    private static final String VERSION_TABLE_KEY = "emr.countersign.version_table";
    private static final String VERSION_FK_KEY = "emr.countersign.version_fk_column";
    private static final String VERSION_NO_KEY = "emr.countersign.version_no_column";

    private static final String DEFAULT_REQUIRED_TYPES = "ADMISSION,FIRST_PROGRESS,DISCHARGE";
    private static final int DEFAULT_DUE_HOURS = 24;
    private static final int OPINION_MAX = 500;
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 100;

    /**
     * 标识符白名单。三个版本表寻址配置是**被拼进 SQL 的标识符**（不是绑定参数），
     * 不校验就是一个注入口——配置表本身是可写的，写入端点在 SysConfigController。
     */
    private static final Pattern IDENT = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");

    /** 缺项码：新增只能追加，既有码不得改名（对齐 EmrIntegrityService.Finding 的既有纪律） */
    public static final String CODE_NOT_COUNTERSIGNED = "NOT_COUNTERSIGNED";
    public static final String CODE_COUNTERSIGN_STALE = "COUNTERSIGN_STALE";

    private final JdbcTemplate jdbc;
    private final ConfigReader configReader;

    // ==================================================================
    // 零、gate、配置与时刻
    // ==================================================================

    /**
     * 审签 gate 三态解析。
     *
     * <p><b>坏配置回落 warn 而不是 off</b>：把 'blocked'、'true'、'on' 这类写错的值当成 off，
     * 等于让一个笔误静默关掉一条法定校验。回落 warn 至少还会提示、还会把事实回带给调用方。
     */
    public String gate() {
        String v = configReader.get(GATE_KEY, "warn");
        v = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "off", "warn", "block" -> v;
            default -> "warn";
        };
    }

    /** 进「待审签」与 gate 判定的病历类型。配置为空/全是空白时回落默认三类，不回落「空集合」——空集合等于静默关闭。 */
    public List<String> requiredTypes() {
        String raw = configReader.get(REQUIRED_TYPES_KEY, DEFAULT_REQUIRED_TYPES);
        var out = parseTypes(raw);
        return out.isEmpty() ? parseTypes(DEFAULT_REQUIRED_TYPES) : out;
    }

    private static List<String> parseTypes(String raw) {
        var set = new LinkedHashSet<String>();
        if (raw != null) {
            for (String s : raw.split(",")) {
                String t = s.trim().toUpperCase(Locale.ROOT);
                if (!t.isEmpty()) set.add(t);
            }
        }
        return List.copyOf(set);
    }

    /** 审签时限（小时）。仅供工作列表标记超期，不拦截任何写入。非正值回落默认。 */
    public int dueHours() {
        int h = configReader.getInt(DUE_HOURS_KEY, DEFAULT_DUE_HOURS);
        return h > 0 ? h : DEFAULT_DUE_HOURS;
    }

    /**
     * 全仓时刻纪律：{@code Instant.now().truncatedTo(MICROS)}。
     * PG 的 timestamptz 只存到微秒，纳秒会被静默<b>四舍五入</b>——写进去 X.123456789、
     * 读出来 X.123457，于是「写什么读什么」的断言在真库上必红（本仓已为此付过四次学费）。
     */
    private static Timestamp nowTs() {
        return Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    // ==================================================================
    // 一、审签动作
    // ==================================================================

    /** 一次审签的结果（成功路径）。失败一律走 {@link #countersign} 的 {@code error} 分支。 */
    public record Result(int code, String message, Map<String, Object> body) {
        public boolean ok() {
            return code == 0;
        }
    }

    private static Result err(int code, String message) {
        return new Result(code, message, null);
    }

    /**
     * 上级审签一份住院病历。
     *
     * <p>写入的是**一条新记录**，被审病历的任何一列都不改（content / signature / doctor_id
     * 连 update 都没有）——审签是在旁边签个字，不是修订正文。
     *
     * <p>重复审签走 <b>{@code on conflict do nothing} + 受影响行数</b>判定，不做「查了再写」
     * （照抄 8014 纪律）：两段式的窗口更大。<b>刻意不靠捕获唯一键异常</b>——PG 在约束冲突后会把
     * 整个事务置为 aborted，捕获得到干净的错误码却留下一个已作废的事务，
     * 调用方（尤其是 {@code @Transactional} 的测试与批量场景）随后的任何一条 SQL 都会连坐报错。
     * 唯一索引 {@code uq_emr_countersign_once(record_id, countersigner_id, content_sha256)}
     * 仍是并发下的最终真相，此处只是不让它以抛异常的方式生效。
     *
     * @param recordId 病历 id（{@code inp_medical_record.id}）
     * @param opinion  审签意见，可空；超 {@value #OPINION_MAX} 字返 5724
     * @param uid      当前登录用户 id；为 null 返 5721
     */
    @Transactional
    public Result countersign(Long recordId, String opinion, Long uid) {
        if (recordId == null) return err(5720, "病历 id 不能为空");
        var rec = record(recordId);
        if (rec == null) return err(5720, "病历不存在：" + recordId);

        if (uid == null) {
            // 匿名审签会让「审签人与书写人是不是同一个人」永远无从核验，审签就此形同虚设。
            return err(5721, "无法识别当前登录用户，不能完成上级审签");
        }

        Long authorId = asLong(rec.get("doctor_id"));
        if (authorId == null) {
            return err(5722, "该病历的书写人未知，无法核验审签人与书写人是否为同一人，不能审签"
                    + "（历史病历 doctor_id 为空，本版不回填、不猜）");
        }
        if (authorId.equals(uid)) {
            return err(5722, "审签人不能与书写人为同一人（自己审自己写的病历不构成上级审签）");
        }

        String norm = opinion == null ? null : opinion.trim();
        if (norm != null && norm.isEmpty()) norm = null;
        if (norm != null && norm.length() > OPINION_MAX) {
            return err(5724, "审签意见超长（最多 " + OPINION_MAX + " 字，收到 " + norm.length() + "）");
        }

        String content = (String) rec.get("content");
        String sha = sha256Hex(content);
        var ver = resolveVersion(recordId);
        Timestamp at = nowTs();

        int inserted = jdbc.update("""
                insert into emr_countersign
                    (record_id, author_id, countersigner_id, countersigner_title,
                     countersigned_at, opinion, content_sha256, content_len,
                     version_id, version_no, version_source)
                values (?, ?, ?, (select title from sys_user where id = ?), ?, ?, ?, ?, ?, ?, ?)
                on conflict (record_id, countersigner_id, content_sha256) do nothing
                """,
                recordId, authorId, uid, uid, at, norm, sha,
                content == null ? 0 : content.length(),
                ver.versionId(), ver.versionNo(), ver.source());
        if (inserted == 0) {
            return err(5723, "该病历的当前正文您已审签过，不可重复审签"
                    + "（正文若有改动会产生新的版本摘要，届时可再次审签）");
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("recordId", recordId);
        body.put("admissionId", asLong(rec.get("admission_id")));
        body.put("recordType", rec.get("record_type"));
        body.put("authorId", authorId);
        body.put("countersignerId", uid);
        body.put("countersignedAt", at.toInstant());
        body.put("contentSha256", sha);
        body.put("versionId", ver.versionId());
        body.put("versionNo", ver.versionNo());
        body.put("versionSource", ver.source());
        body.put("gate", gate());
        body.put("warnings", ver.warnings());
        return new Result(0, "success", body);
    }

    /**
     * 一份病历的全部审签记录（时间正序），每条带 {@code stale} 标志。
     *
     * <p>{@code stale=true} 的含义是**这一次审签之后正文被改过**——摘要就是为此存在的。
     * 计算放在 SQL 里（{@code encode(sha256(convert_to(content,'UTF8')),'hex')}，PG 11+ 内置，
     * 不需要 pgcrypto），与 Java 侧 {@link #sha256Hex} 逐字节同源；
     * 两边同源这件事由 V53CountersignTest 直接断言，不靠口头约定。
     */
    public List<Map<String, Object>> listForRecord(Long recordId) {
        return jdbc.queryForList("""
                select c.id, c.record_id, c.author_id, au.real_name as author_name,
                       c.countersigner_id, cu.real_name as countersigner_name, c.countersigner_title,
                       c.countersigned_at, c.opinion, c.content_sha256, c.content_len,
                       c.version_id, c.version_no, c.version_source,
                       (c.content_sha256 <> encode(sha256(convert_to(r.content, 'UTF8')), 'hex')) as stale
                from emr_countersign c
                join inp_medical_record r on r.id = c.record_id
                left join sys_user au on au.id = c.author_id
                left join sys_user cu on cu.id = c.countersigner_id
                where c.record_id = ?
                order by c.id
                """, recordId);
    }

    // ==================================================================
    // 二、待审签工作列表（按科室 / 按病区 / 按书写医师 / 按时限）
    // ==================================================================

    /**
     * 待审签工作列表。
     *
     * <p>「待审签」= 属于 {@link #requiredTypes()} 白名单 <b>且当前正文没有一条有效审签</b>。
     * 注意判定用的是 <b>有效</b>（摘要与当前正文一致），不是「有没有审签记录」：
     * 上级签完之后住院医又改了正文，这份病历必须重新回到待办里——
     * 「签过了」与「签的是现在这一版」是两件事，只查前者会把被改过的病历静默漏掉。
     *
     * <p>默认排除已归档病案（{@code inp_admission.archived}）：归档后的缺陷属终末质控范畴，
     * 混进日常待办会让列表无限增长。需要盘点存量时传 {@code includeArchived=true}。
     */
    public Result pending(Long deptId, Long wardId, Long doctorId, boolean overdueOnly,
                          boolean includeArchived, Integer limit, Integer offset) {
        if (deptId != null && deptId <= 0) return err(5726, "科室 id 非法：" + deptId);
        if (wardId != null && wardId <= 0) return err(5726, "病区 id 非法：" + wardId);
        if (doctorId != null && doctorId <= 0) return err(5726, "书写医师 id 非法：" + doctorId);
        if (limit != null && (limit <= 0 || limit > MAX_LIMIT)) {
            return err(5726, "limit 非法（1–" + MAX_LIMIT + "）：" + limit);
        }
        if (offset != null && offset < 0) return err(5726, "offset 非法：" + offset);

        int lim = limit == null ? DEFAULT_LIMIT : limit;
        int off = offset == null ? 0 : offset;
        int due = dueHours();
        var types = requiredTypes();

        var args = new ArrayList<Object>();
        var sql = new StringBuilder("""
                select r.id as record_id, r.admission_id, r.record_type, r.title, r.created_at,
                       r.doctor_id as author_id, au.real_name as author_name,
                       a.admission_no, a.dept_id, d.name as dept_name,
                       a.ward_id, w.name as ward_name, a.status as admission_status,
                       a.patient_id, p.name as patient_name,
                       (r.created_at < now() - (? * interval '1 hour')) as overdue,
                       (select count(*) from emr_countersign c where c.record_id = r.id) as countersign_count
                from inp_medical_record r
                join inp_admission a on a.id = r.admission_id
                left join sys_dept d on d.id = a.dept_id
                left join sys_dept w on w.id = a.ward_id
                left join empi_patient p on p.id = a.patient_id
                left join sys_user au on au.id = r.doctor_id
                where r.record_type in (
                """);
        args.add(due);
        sql.append(placeholders(types.size())).append(")\n");
        args.addAll(types);
        sql.append("""
                  and not exists (
                      select 1 from emr_countersign c
                      where c.record_id = r.id
                        and c.content_sha256 = encode(sha256(convert_to(r.content, 'UTF8')), 'hex'))
                """);
        if (!includeArchived) sql.append("  and a.archived = false\n");
        if (deptId != null) {
            sql.append("  and a.dept_id = ?\n");
            args.add(deptId);
        }
        if (wardId != null) {
            sql.append("  and a.ward_id = ?\n");
            args.add(wardId);
        }
        if (doctorId != null) {
            sql.append("  and r.doctor_id = ?\n");
            args.add(doctorId);
        }
        if (overdueOnly) {
            sql.append("  and r.created_at < now() - (? * interval '1 hour')\n");
            args.add(due);
        }
        // 超期的排前面，同档按病历创建时刻正序——最久没人签的排最前
        sql.append("order by overdue desc, r.created_at, r.id\nlimit ? offset ?");
        args.add(lim);
        args.add(off);

        var rows = jdbc.queryForList(sql.toString(), args.toArray());
        var body = new LinkedHashMap<String, Object>();
        body.put("dueHours", due);
        body.put("requiredTypes", types);
        body.put("gate", gate());
        body.put("includeArchived", includeArchived);
        body.put("limit", lim);
        body.put("offset", off);
        body.put("items", rows);
        return new Result(0, "success", body);
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    // ==================================================================
    // 三、gate 判定（纯只读，不 throw；挂点由主控裁决）
    // ==================================================================

    /**
     * 一条审签缺项。{@code code} 稳定机读码，{@code text} 面向用户的中文文案。
     * 结构与 {@link EmrIntegrityService.Finding} 对齐，便于主控把两者并到同一个挡点里。
     */
    public record Finding(String code, Long recordId, String recordType, String text) {}

    /**
     * 某次住院的审签缺项清单（空 = 无缺项）。<b>纯只读、无副作用、不 throw</b>——
     * 挡还是放由挡点按 gate 三态决定，与 {@link EmrIntegrityService#check(Long)} 同口径。
     *
     * <p><b>任何档位下都照算</b>：off 档若跳过计算，返回体里的「审签完整」就会在缺签时谎报 true。
     * gate 只决定「拿这个事实怎么办」，不决定「事实是什么」（v48 病理签发的同一课）。
     *
     * <p>归档与否一律纳入：这是对一次具体住院的判定，不是日常待办列表。
     */
    public List<Finding> findings(Long admissionId) {
        var types = requiredTypes();
        var args = new ArrayList<Object>();
        args.add(admissionId);
        args.addAll(types);
        var rows = jdbc.queryForList("""
                select r.id, r.record_type, r.title,
                       (select count(*) from emr_countersign c where c.record_id = r.id) as total
                from inp_medical_record r
                where r.admission_id = ?
                  and r.record_type in (%s)
                  and not exists (
                      select 1 from emr_countersign c
                      where c.record_id = r.id
                        and c.content_sha256 = encode(sha256(convert_to(r.content, 'UTF8')), 'hex'))
                order by r.id
                """.formatted(placeholders(types.size())), args.toArray());

        var out = new ArrayList<Finding>(rows.size());
        for (var row : rows) {
            Long id = asLong(row.get("id"));
            String type = (String) row.get("record_type");
            String title = (String) row.get("title");
            long total = row.get("total") == null ? 0 : ((Number) row.get("total")).longValue();
            if (total > 0) {
                // 签过，但签的不是现在这一版——这正是「签完又改了」，比从没签过更需要指出来
                out.add(new Finding(CODE_COUNTERSIGN_STALE, id, type,
                        "《" + title + "》上级审签后正文被修改，原审签已失效，需重新审签"));
            } else {
                out.add(new Finding(CODE_NOT_COUNTERSIGNED, id, type,
                        "《" + title + "》未经上级审签"));
            }
        }
        return out;
    }

    /**
     * gate 裁决：{@code blocked=true} 时调用方应返回 {@link #code()} 与 {@link #message()}。
     * warn 档放行但 {@code warnings} 非空，off 档 {@code warnings} 为空但 {@code findings} 照旧是真的。
     */
    public record Verdict(String gate, boolean blocked, int code, String message,
                          List<Finding> findings, List<String> warnings) {}

    /**
     * 出院/归档挡点用的裁决。<b>本车道不把它挂到任何既有写路径上</b>——
     * {@code InpatientService.discharge} 与病案归档是核心写路径，多套 E2E 钉着，
     * 挂哪一个、挂在哪一行由主控裁决（已写进 cross_lane）。这里只把判定备好，一行即可接入。
     */
    public Verdict verdict(Long admissionId) {
        var found = findings(admissionId);
        String gate = gate();
        if (found.isEmpty()) {
            return new Verdict(gate, false, 0, "success", found, List.of());
        }
        String detail = found.size() <= 3
                ? String.join("；", found.stream().map(Finding::text).toList())
                : found.get(0).text() + " 等 " + found.size() + " 条";
        if ("block".equals(gate)) {
            return new Verdict(gate, true, 5725,
                    "未完成上级审签，不能出院/归档：" + detail + "（gate " + GATE_KEY + "=block）",
                    found, List.of());
        }
        if ("off".equals(gate)) {
            return new Verdict(gate, false, 0, "success", found, List.of());
        }
        return new Verdict(gate, false, 0, "success", found,
                List.of("未完成上级审签即放行：" + detail + "（gate=warn）"));
    }

    // ==================================================================
    // 四、版本软引用与摘要
    // ==================================================================

    /** 本次审签绑定到的版本。{@code source} 说明这个绑定是怎么来的、可信到什么程度。 */
    public record VersionRef(Long versionId, Integer versionNo, String source, List<String> warnings) {}

    /**
     * 解析「本次审签的是哪一版」。
     *
     * <p>对 v53 车道 V1 版本表的**软引用**：表名与两个列名走 sys_config 寻址，
     * 任一项对不上（表不存在 / 列不存在 / 配置写得不像标识符）即回落 {@code DIGEST_ONLY}，
     * <b>不报错、不阻断审签</b>——摘要在任何情况下都成立，版本号是锦上添花。
     * V1 落地后无需改一行代码，配置对上即自动点亮；列名若与默认不同，改配置即可。
     */
    public VersionRef resolveVersion(Long recordId) {
        String table = configReader.get(VERSION_TABLE_KEY, "emr_version");
        String fk = configReader.get(VERSION_FK_KEY, "record_id");
        String no = configReader.get(VERSION_NO_KEY, "version_no");
        if (!ident(table) || !ident(fk) || !ident(no)) {
            return new VersionRef(null, null, "DIGEST_ONLY",
                    List.of("版本表寻址配置不是合法标识符，本次审签只绑定正文摘要"));
        }
        try {
            Integer cols = jdbc.queryForObject("""
                    select count(*) from information_schema.columns
                    where table_schema = current_schema() and table_name = ?
                      and column_name in (?, ?, 'emr_type')
                    """, Integer.class, table, fk, no);
            // 要求 3 列齐备（外键列 + 版本号列 + **emr_type 多态判别列**）。
            // 少了 emr_type 就说明这不是 v53 那张多态版本表，宁可回落 DIGEST_ONLY——
            // 在一张不认识的表上按 id 猜版本，绑错的代价比不绑高得多。
            if (cols == null || cols < 3) {
                return new VersionRef(null, null, "DIGEST_ONLY",
                        List.of("病历版本表 " + table + " 尚未就位，本次审签只绑定正文摘要"));
            }
            // 标识符已过 IDENT 白名单，此处拼接安全；record id 仍走绑定参数。
            //
            // **必须带 emr_type='INP'**（v53 复核实测的 D3）：emr_version 是**多态表**，
            // 用 (emr_type, emr_id) 两列共同定位——emr_type='OUTP' 时 emr_id 指 outp_emr.id，
            // 'INP' 时指 inp_medical_record.id。两套 id 各自从 1 递增、**必然重叠**。
            // 只按 emr_id 查而不带 emr_type，住院病历 138 会绑走门诊病历 138 的版本行：
            // 实测出现过 source=VERSION_TABLE、versionNo=99，而那一版的正文是门诊的。
            // 那比绑不上更坏——**绑不上只是信息缺失，绑错是信息错误**，
            // 而审签的全部意义就是「证明签的是哪一版」。
            var rows = jdbc.queryForList(
                    ("select id, %s as vno from %s where emr_type = 'INP' and %s = ? "
                     + "order by %s desc limit 1").formatted(no, table, fk, no), recordId);
            if (rows.isEmpty()) {
                return new VersionRef(null, null, "NO_VERSION_ROW",
                        List.of("该病历在版本表中没有版本记录（版本留痕上线之前书写的病历本就没有），"
                                + "本次审签只绑定正文摘要"));
            }
            var row = rows.get(0);
            Integer vno = row.get("vno") == null ? null : ((Number) row.get("vno")).intValue();
            return new VersionRef(asLong(row.get("id")), vno, "VERSION_TABLE", List.of());
        } catch (Exception e) {
            // 版本表结构与预期不符（列类型不对等）不应连累审签——审签本身必须永远可用
            return new VersionRef(null, null, "DIGEST_ONLY",
                    List.of("读取病历版本表失败，本次审签只绑定正文摘要：" + e.getClass().getSimpleName()));
        }
    }

    private static boolean ident(String s) {
        return s != null && IDENT.matcher(s).matches();
    }

    /**
     * 正文摘要。与 SQL 侧 {@code encode(sha256(convert_to(content,'UTF8')),'hex')} 逐字节同源：
     * 两边都对 UTF-8 字节做 SHA-256 再小写十六进制。{@code convert_to(...,'UTF8')} 显式指定编码，
     * 故与库的服务端编码无关。null 正文按空串计（{@code content} 有 not null，仅防御）。
     */
    public static String sha256Hex(String content) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder(64);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JRE 未提供 SHA-256", e);
        }
    }

    private Map<String, Object> record(Long recordId) {
        var rows = jdbc.queryForList(
                "select id, admission_id, record_type, title, content, doctor_id from inp_medical_record where id = ?",
                recordId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Long asLong(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }
}
