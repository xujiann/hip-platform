package cn.hip.outpatient.service;

import cn.hip.outpatient.entity.OutpEmr;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.service.ConfigReader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v53 车道 V1：病历修订留痕与版本管理（错误码 5700–5719）。
 *
 * <h2>本类补的是一个合规缺口，不是一个功能</h2>
 * 实测现状：{@code DoctorStationService:137} 与 {@code :941} 都是 {@code emrRepository.save(emr)}
 * ——<b>整行覆盖</b>。医生保存第二次，第一次写的内容就永久消失了，库里查不到、日志里也没有。
 * 《电子病历应用管理规范》第二十四条要求病历修改留痕、可追溯；医疗纠纷举证时，
 * 「病历被改过而系统证明不了改了什么」等同于举证不能。
 * <b>所以本类的判据不是「功能好不好用」，是「能不能证明」。</b>
 *
 * <h2>四条纪律（写死在代码结构里，不是口号）</h2>
 * <ol>
 *   <li><b>不伪造初版。</b>本类<b>没有任何</b>「拿当前内容补一条 version_no=1」的路径，
 *       V157 迁移里也一条回填都没有。历史病历查版本列表就是返回空数组 + 一句
 *       「本份病历在版本留痕上线前书写，没有采集到历史版本」——这句话是真的，可以拿去举证。
 *       伪造一条从未真实存在过的版本，比没有留痕更糟。</li>
 *   <li><b>只回看不回滚。</b>本类没有 restore/rollback 方法，将来也不该加：
 *       A 写了 v3、B 一键回滚到 v1 之后，「当前正文的责任人是谁」就说不清了。
 *       需要改回去，由医师本人重新书写并保存，落成 v4——责任链完整。</li>
 *   <li><b>版本不可篡改。</b>本类<b>没有</b>任何 update / delete 版本行的方法，
 *       配套 {@code EmrVersionController} 里<b>一个写映射都没有</b>
 *       （{@code V53EmrVersionTest#controllerExposesNoMutationEndpoint} 用反射钉死这一点）。
 *       可被篡改的留痕在法庭上没有价值。</li>
 *   <li><b>不改既有写路径。</b>本类只提供接缝（{@link #recordOutp}/{@link #recordVersion}），
 *       挂到 {@code DoctorStationService} 哪一行由主控统一做（已写进 cross_lane）。</li>
 * </ol>
 *
 * <h2>gate 三态（{@value #GATE_KEY}，默认 warn，坏值回落 warn 不回落 off）</h2>
 * <ul>
 *   <li><b>off</b>——完全不落版本。逃生口，给「留痕把生产写崩了」这种极端情况用。
 *       开在这一档意味着本版的合规声明当场失效，{@link #settings()} 会把这句话明说出来。</li>
 *   <li><b>warn</b>（默认）——落版本；万一留痕写失败（saved_by 指向不存在的用户、磁盘满、
 *       并发重试仍撞唯一键），<b>回滚到 savepoint、记 ERROR、计数器 +1，但不连累医生那一次保存</b>。
 *       依据是「既有链路不许改坏」：EMR 保存是核心写路径，不能因为侧车表写不进去就让医生存不了病历。</li>
 *   <li><b>block</b>——落版本；留痕写失败就让这次保存一起失败（5705）。
 *       语义是「没有痕迹就不许留下内容」。合规要求最严的院区可以调到这一档，
 *       但要接受「留痕表出问题时医生写不了病历」的后果。</li>
 * </ul>
 * savepoint 是这套语义能成立的<b>唯一</b>技术前提：PostgreSQL 里一条语句触发约束违例后整个事务
 * 进入 aborted，后续任何语句一律 25P02。没有 savepoint，warn 档「不连累医生」就是一句做不到的话
 * （本仓 V50PharmStockTest:797 已为此付过学费）。
 *
 * <h2>并发：三层防线，医生永远看不到异常</h2>
 * <ol>
 *   <li><b>建议锁</b> {@code pg_advisory_xact_lock(5700|5701, emr_id)}——同一份病历的并发保存串行化，
 *       不同病历互不阻塞。classid 按病历类型分开（OUTP=5700 / INP=5701），
 *       否则 {@code outp_emr.id=7} 与 {@code inp_medical_record.id=7} 会互相排队。</li>
 *   <li><b>撞唯一键则重试</b>（{@value #MAX_ATTEMPTS} 次，每次一个独立 savepoint）——
 *       建议锁只在应用内有效，DBA 直连 SQL 插一行照样能撞；重试重新取 max+1。</li>
 *   <li><b>仍失败则按 gate 降级</b>——warn 档返回 {@code status=FAILED} 并记 ERROR，
 *       <b>不把 DuplicateKeyException 抛给医生</b>。</li>
 * </ol>
 * 锁序：调用方先持有 {@code outp_emr} 的行锁（来自 save 的 UPDATE），再取本类的建议锁——
 * 两个接缝点顺序一致，不构成死锁环。
 *
 * <h2>容量：见 {@code perf_note}，此处只记结论</h2>
 * 每次保存多写一份全文快照。写入开销相对 saveEmr 既有的「UPDATE + 删诊断 + N 条插入」是小头，
 * 但<b>表会长</b>：三甲量级估算 10–25 GB/年（推导见交付说明）。三个可用的旋钮：
 * ① {@value #DEDUP_KEY}（默认开，专治前端自动保存刷屏，是最大的一笔）；
 * ② 接缝只挂 MANUAL/SUBMIT、不挂 AUTO；
 * ③ {@code alter table emr_version alter column content set compression lz4}（PG14+，
 * 比 pglz 快 2–3 倍且<b>仍是 SQL 可直接读出的明文</b>——应用层压缩会毁掉「快照可自证」这个唯一目标，
 * V157 已否决）。<b>留痕做得让医生嫌卡，最后会被关掉</b>，这不是假设，是常见结局。
 *
 * <h2>错误码（子段 5700–5719，本版用掉 8 个：5700–5707，5708–5719 空置）</h2>
 * <ul>
 *   <li>5700 病历类型非法（只接受 OUTP / INP）</li>
 *   <li>5701 病历 id 非法</li>
 *   <li>5702 保存来源非法（只接受 MANUAL / AUTO / SUBMIT）</li>
 *   <li>5703 版本不存在</li>
 *   <li>5704 单版快照超出字符上限（<b>仅 strict 入口返回；接缝永不截断、照写全文</b>）</li>
 *   <li>5705 版本留痕写入失败且 gate=block，本次保存一并失败</li>
 *   <li>5706 分页/版本号入参非法</li>
 *   <li>5707 病历主记录不存在（与当前正文对比时）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmrVersionService {

    // ==================================================================
    // 零、常量与配置键
    // ==================================================================

    /** gate 配置键（V157 已随地基写入，默认值 warn） */
    public static final String GATE_KEY = "emr.version.gate";
    /** 去重开关配置键（V157 已随地基写入，默认值 on） */
    public static final String DEDUP_KEY = "emr.version.dedup";
    /** 单版快照字符数上限配置键（V157 已随地基写入，默认值 1000000） */
    public static final String MAX_CHARS_KEY = "emr.version.max-content-chars";

    /** 门诊病历（{@code outp_emr.id}）。沿用 {@code emr_amendment.emr_type} 的既有口径。 */
    public static final String OUTP = "OUTP";
    /** 住院病历（{@code inp_medical_record.id}）。 */
    public static final String INP = "INP";

    public static final String MANUAL = "MANUAL";
    public static final String AUTO = "AUTO";
    public static final String SUBMIT = "SUBMIT";

    /** {@link RecordResult#status()} 取值 */
    public static final String ST_RECORDED = "RECORDED";
    public static final String ST_DEDUPED = "DEDUPED";
    public static final String ST_SKIPPED_OFF = "SKIPPED_OFF";
    public static final String ST_SKIPPED_INVALID = "SKIPPED_INVALID";
    public static final String ST_FAILED = "FAILED";

    /** {@link FieldDiff#status()} 取值 */
    public static final String D_ADDED = "ADDED";
    public static final String D_REMOVED = "REMOVED";
    public static final String D_MODIFIED = "MODIFIED";
    public static final String D_UNCHANGED = "UNCHANGED";

    private static final int LOCK_NS_OUTP = 5700;
    private static final int LOCK_NS_INP = 5701;
    private static final int MAX_ATTEMPTS = 3;
    private static final int DEFAULT_MAX_CHARS = 1_000_000;
    private static final int LIST_MAX_LIMIT = 200;
    private static final int LIST_DEFAULT_LIMIT = 50;
    /** 差异片段的展示上限。<b>只截「变化片段」这个便利视图</b>，from/to 全文一律不截。 */
    private static final int CHANGED_SEGMENT_MAX = 2000;

    /**
     * 门诊病历的规范字段集，<b>与 CA 签名口径严格对齐</b>：
     * {@code signEmr} 签的就是这五段以 '|' 拼接的结果（DoctorStationService:930）。
     *
     * <p><b>刻意不含 {@code contentJson}/{@code templateId}</b>：结构化侧车在保存时已经被渲染进
     * {@code presentIllness}（V139 的处置），再快照一份等于同一段内容记两遍，
     * 对比时会冒出「现病史没变、content_json 变了」这种自相矛盾的差异行。
     * 被签名的是什么，被快照的就是什么——这是举证时唯一说得清的口径。
     */
    private static final List<String> OUTP_FIELDS =
            List.of("chiefComplaint", "presentIllness", "pastHistory", "physicalExam", "advice");

    /**
     * 住院病历的规范字段集。
     *
     * <p>{@code inp_medical_record} 与 {@code outp_emr} 字段完全不同：住院侧正文是<b>单列</b>
     * {@code content}（V133 起为 text），另有 v34 三级查房的三段结构化列。
     * {@code title} 纳入是有意的——文书标题被改（「入院记录」改成「病程记录」）是实打实的修订，
     * 而它不在 {@code content} 里，不快照就丢了。
     * {@code round_doctor_id} 不纳入：那是关联人不是正文，改它属另一类事件。
     */
    private static final List<String> INP_FIELDS =
            List.of("title", "content", "roundLevel", "roundOpinion", "superiorCorrection");

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("chiefComplaint", "主诉"),
            Map.entry("presentIllness", "现病史"),
            Map.entry("pastHistory", "既往史"),
            Map.entry("physicalExam", "体格检查"),
            Map.entry("advice", "处理意见"),
            Map.entry("title", "文书标题"),
            Map.entry("content", "病历正文"),
            Map.entry("roundLevel", "查房级别"),
            Map.entry("roundOpinion", "查房意见"),
            Map.entry("superiorCorrection", "上级修正意见"));

    /**
     * 规范化 JSON 专用的 {@code ObjectMapper}——<b>刻意不注入容器里那个</b>。
     *
     * <p>content_hash 的稳定性依赖序列化结果逐字节可重现。容器里的 ObjectMapper 是全局可配置的
     * （任何人加一条 {@code spring.jackson.serialization.indent-output=true} 都会改变输出），
     * 那样一来历史快照的格式与新快照不一致、dedup 全线失效，而且<b>不报任何错</b>。
     * 私有实例把这个风险从「配置问题」降级成「改本文件才可能发生」。
     */
    private static final ObjectMapper CANON = new ObjectMapper();

    /** savepoint 名称序号：同一事务里可能多次调用（保存后紧接着签名），名字必须互不覆盖。 */
    private static final AtomicLong CALL_SEQ = new AtomicLong();

    private final JdbcTemplate jdbc;
    private final ConfigReader configReader;

    // 运行计数器（进程内，重启归零）。warn 档的失败是**静默**的——不暴露计数就等于没人知道
    // 留痕已经连续失败了两天。{@link #settings()} 把它们摆出来给运维看。
    private final AtomicLong recordedCount = new AtomicLong();
    private final AtomicLong dedupedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    private final AtomicLong skippedCount = new AtomicLong();
    private volatile String lastFailure;
    private volatile Instant lastFailureAt;

    // ==================================================================
    // 一、gate 与配置
    // ==================================================================

    /**
     * gate 三态解析。<b>坏配置回落 warn 而不是 off</b>：把 'blocked'、'true'、'on' 这类写错的值
     * 当成 off，等于让一个笔误静默关掉一条法定留痕。
     */
    public String gate() {
        String v = configReader.get(GATE_KEY, "warn");
        v = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "off", "warn", "block" -> v;
            default -> "warn";
        };
    }

    /** 去重开关。默认开；只有显式写成 off/0/false 才关（同样是「坏值不静默关功能」的方向）。 */
    public boolean dedup() {
        String v = configReader.get(DEDUP_KEY, "on");
        v = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        return !("off".equals(v) || "0".equals(v) || "false".equals(v) || "no".equals(v));
    }

    /** 单版快照字符上限。非正值回落默认——0 会让每一次留痕都超限。 */
    public int maxContentChars() {
        int v = configReader.getInt(MAX_CHARS_KEY, DEFAULT_MAX_CHARS);
        return v > 0 ? v : DEFAULT_MAX_CHARS;
    }

    /**
     * 全仓时刻纪律：{@code Instant.now().truncatedTo(MICROS)}。
     * PG 的 timestamptz 只存到微秒且按<b>四舍五入</b>落盘，Java Instant 带纳秒——
     * 不截断就会出现「写进去 12:00:00.0000005、读回来 12:00:00.000001」的差 100ns 假越界。
     * 本仓已被这个根因炸过四次。
     */
    private static Instant nowMicros() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    // ==================================================================
    // 二、写入接缝
    // ==================================================================

    /**
     * 一次留痕的结果。<b>成功与失败都返回本对象、都不抛异常</b>（block 档的 5705 是唯一例外），
     * 调用方（既有写路径）可以完全忽略返回值——这是「既有链路不许改坏」的形状要求。
     */
    public record RecordResult(String status, Long versionId, Integer versionNo,
                               String contentHash, int contentLen, String gate,
                               List<String> warnings) {

        public boolean recorded() {
            return ST_RECORDED.equals(status);
        }

        public Map<String, Object> asMap() {
            var m = new LinkedHashMap<String, Object>();
            m.put("status", status);
            m.put("versionId", versionId);
            m.put("versionNo", versionNo);
            m.put("contentHash", contentHash);
            m.put("contentLen", contentLen);
            m.put("gate", gate);
            m.put("warnings", warnings);
            return m;
        }
    }

    /**
     * <b>门诊病历留痕接缝——主控接入用的就是这一个方法。</b>
     *
     * <p>放在 {@code emrRepository.save(emr)} <b>之后</b>调用（新建病历要等 save 分配到 id），
     * 一行即可：{@code emrVersionService.recordOutp(emr, EmrVersionService.MANUAL, doctorId);}
     * 返回值可丢弃。
     *
     * @param emr    刚落库的门诊病历实体；为 null 或 id 为 null 时按 5701 降级（warn 档只记 ERROR）
     * @param source {@link #MANUAL} 手动保存 / {@link #AUTO} 前端自动保存 / {@link #SUBMIT} 签名定稿
     * @param userId 保存人；<b>允许为 null</b>（服务层直调、无登录上下文的既有单测与 E2E），
     *               此时如实落 null，<b>不做任何「回填成当班医生」的猜测</b>——猜错了就是把责任安在别人头上
     */
    @Transactional
    public RecordResult recordOutp(OutpEmr emr, String source, Long userId) {
        if (emr == null) {
            return degrade(5701, "门诊病历实体为空，无法留痕", gate(), false);
        }
        return recordVersion(OUTP, emr.getId(), outpFields(emr), source, userId);
    }

    /** 门诊病历实体 → 规范字段集。原样取值，<b>不做任何归一化</b>（见 {@link #canonicalJson}）。 */
    public static Map<String, String> outpFields(OutpEmr emr) {
        var m = new LinkedHashMap<String, String>();
        m.put("chiefComplaint", emr.getChiefComplaint());
        m.put("presentIllness", emr.getPresentIllness());
        m.put("pastHistory", emr.getPastHistory());
        m.put("physicalExam", emr.getPhysicalExam());
        m.put("advice", emr.getAdvice());
        return m;
    }

    /**
     * 住院病历字段集。住院侧实体在 {@code hip-inpatient} 模块、本模块看不见它，
     * 故按值传参——住院接缝由主控在 {@code InpEmrController}/{@code InpatientService} 侧调用。
     */
    public static Map<String, String> inpFields(String title, String content, String roundLevel,
                                                String roundOpinion, String superiorCorrection) {
        var m = new LinkedHashMap<String, String>();
        m.put("title", title);
        m.put("content", content);
        m.put("roundLevel", roundLevel);
        m.put("roundOpinion", roundOpinion);
        m.put("superiorCorrection", superiorCorrection);
        return m;
    }

    /**
     * 通用留痕接缝（宽松档）。入参非法、写入失败一律按 gate 降级，<b>不抛异常</b>
     * （block 档的 5705 除外）。超长快照<b>永不截断</b>，记 ERROR 后照写全文——
     * 截断后的快照拿去举证等于伪证。
     */
    @Transactional
    public RecordResult recordVersion(String emrType, Long emrId, Map<String, String> fields,
                                      String source, Long userId) {
        return doRecord(emrType, emrId, fields, source, userId, false);
    }

    /**
     * 严格档留痕：入参非法直接抛对应错误码，超长快照抛 5704 <b>拒绝写入</b>，写入失败抛 5705。
     * 给「宁可不保存也不能没有痕迹」的调用方用；<b>既有写路径不要用这个</b>。
     */
    @Transactional
    public RecordResult recordVersionStrict(String emrType, Long emrId, Map<String, String> fields,
                                            String source, Long userId) {
        return doRecord(emrType, emrId, fields, source, userId, true);
    }

    private RecordResult doRecord(String emrType, Long emrId, Map<String, String> fields,
                                  String source, Long userId, boolean strict) {
        String gate = gate();

        // ---- 入参校验。off 档也要先校验：把「类型写错」当成「gate 关着所以无所谓」会让
        //      接缝一直是坏的，等哪天开到 warn 才发现一年没落上版本。
        if (!OUTP.equals(emrType) && !INP.equals(emrType)) {
            return degrade(5700, "病历类型非法：" + emrType + "（只接受 OUTP / INP）", gate, strict);
        }
        if (emrId == null || emrId <= 0) {
            return degrade(5701, "病历 id 非法：" + emrId, gate, strict);
        }
        if (!MANUAL.equals(source) && !AUTO.equals(source) && !SUBMIT.equals(source)) {
            // 来源标错等于把「自动保存」说成「医生亲手点了保存」，是证据链上的失真，不能默默当 MANUAL
            return degrade(5702, "保存来源非法：" + source + "（只接受 MANUAL / AUTO / SUBMIT）", gate, strict);
        }

        if ("off".equals(gate)) {
            skippedCount.incrementAndGet();
            return new RecordResult(ST_SKIPPED_OFF, null, null, null, 0, gate,
                    List.of("gate " + GATE_KEY + "=off：本次保存未落任何版本记录，"
                            + "本版「病历修改留痕可追溯」的合规声明在这一档位下不成立"));
        }

        String content = canonicalJson(fields);
        int len = content.length();
        String hash = sha256Hex(content);
        var warnings = new ArrayList<String>();

        if (len > maxContentChars()) {
            String msg = "单版快照 " + len + " 字符，超出上限 " + maxContentChars()
                    + "（" + MAX_CHARS_KEY + "）；正文里可能混进了 base64 图片之类的非文本内容";
            if (strict) {
                throw new HipBizException(5704, msg);
            }
            // 接缝档：**照写全文**。截断的快照是伪证，宁可让表长胖也不能让内容失真。
            log.error("[emr-version] 超长快照仍照写全文（不截断）：emrType={} emrId={} {}", emrType, emrId, msg);
            warnings.add(msg + "（已完整写入，未截断）");
        }

        boolean tx = TransactionSynchronizationManager.isActualTransactionActive();
        long callId = CALL_SEQ.incrementAndGet();
        RuntimeException last = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String sp = "hip_emr_ver_" + callId + "_" + attempt;
            if (tx) {
                jdbc.execute("savepoint " + sp);
            }
            try {
                // 建议锁每轮都取：子事务回滚可能把上一轮取到的锁一并释放，重取是幂等的
                lock(emrType, emrId);

                if (dedup() && !SUBMIT.equals(source)) {
                    var prev = latestMeta(emrType, emrId);
                    if (prev != null && hash.equals(prev.hash())) {
                        // 内容逐字节没变。自动保存每 30 秒一次，医生泡杯茶回来能刷出 20 条
                        // 一模一样的版本——那不是留痕，是把真正的修改淹掉。
                        if (tx) {
                            jdbc.execute("release savepoint " + sp);
                        }
                        dedupedCount.incrementAndGet();
                        return new RecordResult(ST_DEDUPED, prev.id(), prev.versionNo(), hash, len, gate,
                                List.copyOf(warnings));
                    }
                }

                Integer next = jdbc.queryForObject(
                        "select coalesce(max(version_no), 0) + 1 from emr_version where emr_type = ? and emr_id = ?",
                        Integer.class, emrType, emrId);
                int no = next == null ? 1 : next;

                Long id = jdbc.queryForObject("""
                        insert into emr_version
                            (emr_type, emr_id, version_no, source, content, content_len, content_hash,
                             saved_by, saved_at, saved_on)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::date)
                        returning id
                        """, Long.class,
                        emrType, emrId, no, source, content, len, hash, userId,
                        java.sql.Timestamp.from(nowMicros()),
                        // saved_on 走 ?::date 传 ISO 串：**绕开 java.sql.Date 的时区往返**。
                        // 业务日必须是 BusinessDates.today()（Asia/Shanghai），不是 date(saved_at)——
                        // DB 会话时区与业务时区是两套口径，跨 00:00–08:00 的夜班两者差一天（1.1.9 已付学费）。
                        BusinessDates.today().toString());

                if (tx) {
                    jdbc.execute("release savepoint " + sp);
                }
                recordedCount.incrementAndGet();
                return new RecordResult(ST_RECORDED, id, no, hash, len, gate, List.copyOf(warnings));

            } catch (RuntimeException e) {
                last = e;
                if (tx) {
                    // 这一行是 warn 档「不连累医生本次保存」的全部技术依据：
                    // 不回滚到 savepoint，PG 会把整个事务判 aborted，医生的病历也一并存不进去。
                    jdbc.execute("rollback to savepoint " + sp);
                }
                boolean retryable = e instanceof DuplicateKeyException
                        || (e instanceof DataAccessException && isUniqueViolation(e));
                if (retryable && attempt < MAX_ATTEMPTS) {
                    log.warn("[emr-version] 版本号撞唯一键，重取 max+1 重试（第 {} 次）：emrType={} emrId={}",
                            attempt, emrType, emrId);
                    continue;
                }
                break;
            }
        }

        return fail(emrType, emrId, gate, strict, warnings, last);
    }

    /** 入参非法时的降级：strict 抛码；block 档也抛（没有痕迹就不许留下内容）；warn/off 记 ERROR 后放行。 */
    private RecordResult degrade(int code, String message, String gate, boolean strict) {
        if (strict || "block".equals(gate)) {
            throw new HipBizException(code, message);
        }
        skippedCount.incrementAndGet();
        lastFailure = code + " " + message;
        lastFailureAt = nowMicros();
        log.error("[emr-version] 留痕入参非法，本次未落版本（gate={}）：{} {}", gate, code, message);
        return new RecordResult(ST_SKIPPED_INVALID, null, null, null, 0, gate,
                List.of(code + " " + message));
    }

    /** 写入失败时的降级：strict / block 抛 5705；warn 记 ERROR + 计数器 +1，不连累调用方。 */
    private RecordResult fail(String emrType, Long emrId, String gate, boolean strict,
                              List<String> warnings, RuntimeException cause) {
        String reason = cause == null ? "未知原因"
                : cause.getClass().getSimpleName() + ": " + rootMessage(cause);
        String msg = "病历版本留痕写入失败（emrType=" + emrType + " emrId=" + emrId + "）：" + reason;
        failedCount.incrementAndGet();
        lastFailure = msg;
        lastFailureAt = nowMicros();
        log.error("[emr-version] {}（gate={}）", msg, gate, cause);
        if (strict || "block".equals(gate)) {
            throw new HipBizException(5705, msg + "。gate " + GATE_KEY
                    + "=block 的语义是「没有痕迹就不许留下内容」，本次保存一并失败");
        }
        var w = new ArrayList<>(warnings);
        w.add(msg + "（gate=warn：本次保存照常提交，但这一版没有留痕）");
        return new RecordResult(ST_FAILED, null, null, null, 0, gate, List.copyOf(w));
    }

    private void lock(String emrType, Long emrId) {
        // 用 ResultSetExtractor 而非 queryForObject：pg_advisory_xact_lock 返回 void（OID 2278），
        // queryForObject 会去读这一列的值，走 pgjdbc「不支持的转换」分支。我们只要拿到锁。
        // objid 是 int4，emr_id 是 bigint——取模只是防御性收窄，撞号只会多串行化一点，不影响正确性。
        jdbc.query("select pg_advisory_xact_lock(?, ?)",
                (ResultSetExtractor<Void>) rs -> null,
                OUTP.equals(emrType) ? LOCK_NS_OUTP : LOCK_NS_INP,
                (int) (emrId % Integer.MAX_VALUE));
    }

    private static boolean isUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException se && "23505".equals(se.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return m == null ? t.getClass().getName() : m.lines().findFirst().orElse(m);
    }

    // ==================================================================
    // 三、规范化与摘要
    // ==================================================================

    /**
     * 规范化 JSON：{@code {字段名: 字符串值|null}}，<b>键按字典序</b>（供 content_hash 稳定）。
     *
     * <p><b>值一律原样收录，不做任何归一化</b>——空串不折成 null、不 trim、不去空白。
     * 理由是这是证据：把 {@code ""} 改写成 {@code null} 是替医生编辑内容。
     * 代价老实说清楚：前端某次传 {@code ""}、下次传 {@code null} 会产生一条肉眼看不出差异的版本，
     * dedup 只认逐字节相同，挡不住这种。宁可多一条真版本，不要少一次真修改。
     *
     * <p>本方法<b>永远返回非 null 的 JSON 对象</b>，五段全空时也写出
     * {@code {"advice":null,...}}——「当时这份病历有哪几个字段」本身也是证据。
     * {@code content} 列声明可空只是给直连 SQL 兜底。
     */
    public static String canonicalJson(Map<String, String> fields) {
        var sorted = new TreeMap<String, String>();
        if (fields != null) {
            sorted.putAll(fields);
        }
        try {
            return CANON.writeValueAsString(sorted);
        } catch (JsonProcessingException e) {
            // TreeMap<String,String> 序列化不可能失败；真失败了也绝不能把留痕吞掉
            throw new IllegalStateException("病历快照序列化失败", e);
        }
    }

    /** 从规范化 JSON 还原字段集。解析失败返回空表（历史脏数据不应让整个查询 500）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, String> parseFields(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            var raw = CANON.readValue(json, Map.class);
            var out = new LinkedHashMap<String, String>();
            ((Map<String, Object>) raw).forEach((k, v) -> out.put(k, v == null ? null : String.valueOf(v)));
            return out;
        } catch (Exception e) {
            log.warn("[emr-version] 快照 JSON 解析失败，按空字段集处理：{}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 规范化 JSON 的 SHA-256（小写 hex）。与 SQL 侧
     * {@code encode(sha256(convert_to(content,'UTF8')),'hex')} 逐字节同源，
     * {@code V53EmrVersionTest#javaAndSqlDigestAgree} 直接断言这一点，不靠口头约定。
     *
     * <p><b>用途只有一个：dedup。</b>它不是防篡改哈希，本类不做任何防篡改声明——
     * 真要防篡改得靠库权限与审计，不是靠一个和内容存在同一行里的摘要。
     */
    public static String sha256Hex(String content) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((content == null ? "" : content).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var sb = new StringBuilder(64);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JRE 未提供 SHA-256", e);
        }
    }

    // ==================================================================
    // 四、只读查询
    // ==================================================================

    /** 只读查询的统一返回（与 {@code CountersignService.Result} 同形，便于主控并到同一层）。 */
    public record Result(int code, String message, Map<String, Object> body) {
        public boolean ok() {
            return code == 0;
        }
    }

    private static Result err(int code, String message) {
        return new Result(code, message, null);
    }

    /** 版本行的元信息（<b>不含 content</b>）。 */
    public record VersionMeta(Long id, int versionNo, String source, int contentLen, String contentHash,
                              Long savedBy, String savedByName, Instant savedAt, String savedOn) {}

    private static final RowMapper<VersionMeta> META = (rs, i) -> new VersionMeta(
            rs.getLong("id"), rs.getInt("version_no"), rs.getString("source"),
            rs.getInt("content_len"), rs.getString("content_hash"),
            rs.getObject("saved_by") == null ? null : rs.getLong("saved_by"),
            rs.getString("saved_by_name"),
            rs.getTimestamp("saved_at").toInstant(),
            // saved_on 在 SQL 里已经 ::text 掉了：**绝不用 java.sql.Date.toLocalDate()**，
            // 它按 JVM 时区换算，跨时区部署会读出差一天的业务日（1.2.2 / v48 都栽在这个上）。
            rs.getString("saved_on"));

    /** 只用于 dedup 的极小投影（连 content_len 都不读，更不碰 TOAST 出来的 content 本体）。 */
    private record LatestMeta(Long id, Integer versionNo, String hash) {}

    private LatestMeta latestMeta(String emrType, Long emrId) {
        var rows = jdbc.query("""
                select id, version_no, content_hash from emr_version
                where emr_type = ? and emr_id = ? order by version_no desc limit 1
                """,
                (rs, i) -> new LatestMeta(rs.getLong("id"), rs.getInt("version_no"), rs.getString("content_hash")),
                emrType, emrId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 版本列表（倒序，分页）。<b>不返回 content 全文</b>——列表页不需要，返了会很慢：
     * 一页 50 版 × 住院病历动辄几千字 = 每次翻页从 TOAST 里拉几 MB。
     * 每条给 {@code contentLen} / {@code deltaLen} / {@code sameAsPrevious}，够前端画出「改了多少」。
     *
     * @param withChangedFields 是否额外算出「这一版相对上一版改了哪几个字段」。
     *                          <b>默认关</b>：它需要把本页 + 前一版的 content 全读出来，
     *                          正是上面刚说过要避免的开销。只在用户点开「详细」时才打开。
     *                          <b>即便打开也只返字段名，不返正文。</b>
     */
    public Result list(String emrType, Long emrId, Integer limit, Integer offset, boolean withChangedFields) {
        var bad = checkTarget(emrType, emrId);
        if (bad != null) {
            return bad;
        }
        if (limit != null && (limit <= 0 || limit > LIST_MAX_LIMIT)) {
            return err(5706, "limit 非法（1–" + LIST_MAX_LIMIT + "）：" + limit);
        }
        if (offset != null && offset < 0) {
            return err(5706, "offset 非法：" + offset);
        }
        int lim = limit == null ? LIST_DEFAULT_LIMIT : limit;
        int off = offset == null ? 0 : offset;

        Long total = jdbc.queryForObject(
                "select count(*) from emr_version where emr_type = ? and emr_id = ?",
                Long.class, emrType, emrId);
        long cnt = total == null ? 0 : total;

        // where (emr_type, emr_id) + order by version_no desc 正好走 uk_emr_version_no 的最左前缀，
        // 倒序由 PG 反向扫同一个索引完成——V157 刻意没建第二个索引，就是靠这一点。
        var rows = jdbc.query("""
                select v.id, v.version_no, v.source, v.content_len, v.content_hash,
                       v.saved_by, u.real_name as saved_by_name, v.saved_at, v.saved_on::text as saved_on
                from emr_version v
                left join sys_user u on u.id = v.saved_by
                where v.emr_type = ? and v.emr_id = ?
                order by v.version_no desc
                limit ? offset ?
                """, META, emrType, emrId, lim, off);

        Map<Integer, Map<String, String>> snapshots = Map.of();
        if (withChangedFields && !rows.isEmpty()) {
            int maxNo = rows.get(0).versionNo();
            int minNo = rows.get(rows.size() - 1).versionNo();
            snapshots = contentsBetween(emrType, emrId, minNo - 1, maxNo);
        }

        var items = new ArrayList<Map<String, Object>>(rows.size());
        for (var m : rows) {
            var item = metaMap(m);
            // 与「上一版」比，而不是与「列表里的下一行」比：翻页边界上两者不是一回事
            int prevNo = m.versionNo() - 1;
            Integer prevLen = prevContentLen(rows, m.versionNo());
            item.put("deltaLen", prevLen == null ? null : m.contentLen() - prevLen);
            item.put("firstVersion", m.versionNo() == 1);
            if (withChangedFields) {
                var cur = snapshots.get(m.versionNo());
                var prev = snapshots.get(prevNo);
                item.put("changedFields", cur == null ? List.of() : changedFieldNames(emrType, prev, cur));
            }
            items.add(item);
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("emrType", emrType);
        body.put("emrId", emrId);
        body.put("total", cnt);
        body.put("limit", lim);
        body.put("offset", off);
        body.put("gate", gate());
        body.put("dedup", dedup());
        body.put("items", items);
        if (cnt == 0) {
            // 这句话是真的，可以拿去举证。**没有任何代码路径会去伪造一条初版把它填上。**
            body.put("notice", "本份病历没有任何版本记录：版本留痕上线之前书写的病历本就没有采集到历史版本。"
                    + "本版不回填、不伪造初版——伪造一条从未真实存在过的版本，比没有留痕更糟。");
        }
        body.put("notes", List.of(
                "只回看不回滚：本模块不提供「恢复到某一版」，回滚会让「当前正文的责任人是谁」变得不清楚",
                "版本不可篡改：本模块没有任何修改/删除版本的端点",
                "saved_by 为 null 表示这次保存没有登录上下文，查询侧如实回 null，不做任何回填猜测"));
        return new Result(0, "success", body);
    }

    private static Integer prevContentLen(List<VersionMeta> page, int versionNo) {
        for (var m : page) {
            if (m.versionNo() == versionNo - 1) {
                return m.contentLen();
            }
        }
        return null;
    }

    private Map<Integer, Map<String, String>> contentsBetween(String emrType, Long emrId, int fromNo, int toNo) {
        var out = new LinkedHashMap<Integer, Map<String, String>>();
        jdbc.query("""
                select version_no, content from emr_version
                where emr_type = ? and emr_id = ? and version_no between ? and ?
                """,
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        out.put(rs.getInt("version_no"), parseFields(rs.getString("content"))),
                emrType, emrId, Math.max(fromNo, 1), toNo);
        return out;
    }

    private static List<String> changedFieldNames(String emrType, Map<String, String> prev, Map<String, String> cur) {
        var names = new ArrayList<String>();
        for (String f : fieldOrder(emrType, prev, cur)) {
            String a = prev == null ? null : prev.get(f);
            String b = cur.get(f);
            if (!java.util.Objects.equals(a, b)) {
                names.add(f);
            }
        }
        return names;
    }

    /**
     * 单版查看：<b>返回完整 content</b>，并额外拆成 {@code fields}（字段名 → 值）方便前端分段渲染。
     * 这是「第 2 版当时到底写的是什么」这个问题的唯一答案来源——全文快照是自解释的，
     * 单独拎一行出来就能读，这正是 V157 选择存全文而不是存 diff 的理由。
     */
    public Result get(String emrType, Long emrId, Integer versionNo) {
        var bad = checkTarget(emrType, emrId);
        if (bad != null) {
            return bad;
        }
        if (versionNo == null || versionNo < 1) {
            return err(5706, "版本号非法：" + versionNo);
        }
        var snap = loadSnapshot(emrType, emrId, versionNo);
        if (snap == null) {
            return err(5703, "版本不存在：" + emrType + "#" + emrId + " v" + versionNo);
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("emrType", emrType);
        body.put("emrId", emrId);
        body.putAll(snapshotMap(emrType, snap));
        return new Result(0, "success", body);
    }

    /** 一版的完整快照（含 content 与解析后的字段）。{@code versionNo=null} 表示「当前正文」虚拟快照。 */
    public record Snapshot(VersionMeta meta, String content, Map<String, String> fields, String label) {}

    private Snapshot loadSnapshot(String emrType, Long emrId, int versionNo) {
        var rows = jdbc.query("""
                select v.id, v.version_no, v.source, v.content_len, v.content_hash,
                       v.saved_by, u.real_name as saved_by_name, v.saved_at, v.saved_on::text as saved_on,
                       v.content
                from emr_version v
                left join sys_user u on u.id = v.saved_by
                where v.emr_type = ? and v.emr_id = ? and v.version_no = ?
                """,
                (rs, i) -> {
                    var meta = META.mapRow(rs, i);
                    String content = rs.getString("content");
                    return new Snapshot(meta, content, parseFields(content), "v" + meta.versionNo());
                }, emrType, emrId, versionNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 任意两版的<b>结构化差异</b>：逐字段给出「哪个字段、从什么改成什么」，
     * 不是甩两段文本让人自己看。病历正文是多字段的（主诉/现病史/既往史/查体/处理意见…），
     * 按字段对比才有用。
     *
     * <p>未变化的字段<b>不回正文</b>（只回长度）：一次对比要是把两份全文各回一遍，
     * 住院病历动辄几千字，前端拿到的 90% 是没变的内容。要看全文请调单版查看。
     */
    public Result compare(String emrType, Long emrId, Integer fromNo, Integer toNo) {
        var bad = checkTarget(emrType, emrId);
        if (bad != null) {
            return bad;
        }
        if (fromNo == null || fromNo < 1) {
            return err(5706, "起始版本号非法：" + fromNo);
        }
        if (toNo == null || toNo < 1) {
            return err(5706, "目标版本号非法：" + toNo);
        }
        var a = loadSnapshot(emrType, emrId, fromNo);
        if (a == null) {
            return err(5703, "版本不存在：" + emrType + "#" + emrId + " v" + fromNo);
        }
        var b = loadSnapshot(emrType, emrId, toNo);
        if (b == null) {
            return err(5703, "版本不存在：" + emrType + "#" + emrId + " v" + toNo);
        }
        return new Result(0, "success", diffBody(emrType, emrId, a, b));
    }

    /**
     * 某一版与<b>当前正文</b>的对比。
     *
     * <p>这是实务里问得最多的一句话——「现在这份病历和上级签字时那一版差在哪」——
     * 而它不是「两版对比」，因为当前正文<b>不是</b>一个版本行。
     * 返回体里那一侧的 {@code versionNo} 是 null、{@code label} 是 {@code CURRENT}，
     * <b>本方法不写库、不生成任何版本行</b>：把当前内容记成一版，就是在伪造一次并不存在的保存动作。
     *
     * <p>另：本方法直读 {@code outp_emr} / {@code inp_medical_record}，
     * 与 {@code CountersignService} 判 stale 的口径同源但独立——它算的是摘要，这里算的是逐字段差异。
     */
    public Result compareWithCurrent(String emrType, Long emrId, Integer fromNo) {
        var bad = checkTarget(emrType, emrId);
        if (bad != null) {
            return bad;
        }
        if (fromNo == null || fromNo < 1) {
            return err(5706, "起始版本号非法：" + fromNo);
        }
        var a = loadSnapshot(emrType, emrId, fromNo);
        if (a == null) {
            return err(5703, "版本不存在：" + emrType + "#" + emrId + " v" + fromNo);
        }
        var live = liveFields(emrType, emrId);
        if (live == null) {
            return err(5707, "病历主记录不存在："
                    + (OUTP.equals(emrType) ? "outp_emr" : "inp_medical_record") + "#" + emrId
                    + "（版本留痕刻意不设外键，主表行被删后留痕仍在——此时只能回看版本，无法与当前正文对比）");
        }
        String json = canonicalJson(live);
        var meta = new VersionMeta(null, 0, "CURRENT", json.length(), sha256Hex(json), null, null, null, null);
        var b = new Snapshot(meta, json, live, "CURRENT");
        var body = diffBody(emrType, emrId, a, b);
        body.put("notice", "右侧是数据库里的当前正文，不是一个版本记录——本次对比不写库、不生成版本行。"
                + "当前正文与最后一版不一致，说明这次修改发生在留痕接缝之外（或 gate 曾开在 off）。");
        return new Result(0, "success", body);
    }

    private Map<String, String> liveFields(String emrType, Long emrId) {
        if (OUTP.equals(emrType)) {
            var rows = jdbc.query("""
                    select chief_complaint, present_illness, past_history, physical_exam, advice
                    from outp_emr where id = ?
                    """,
                    (rs, i) -> {
                        var m = new LinkedHashMap<String, String>();
                        m.put("chiefComplaint", rs.getString("chief_complaint"));
                        m.put("presentIllness", rs.getString("present_illness"));
                        m.put("pastHistory", rs.getString("past_history"));
                        m.put("physicalExam", rs.getString("physical_exam"));
                        m.put("advice", rs.getString("advice"));
                        return (Map<String, String>) m;
                    }, emrId);
            return rows.isEmpty() ? null : rows.get(0);
        }
        var rows = jdbc.query("""
                select title, content, round_level, round_opinion, superior_correction
                from inp_medical_record where id = ?
                """,
                (rs, i) -> inpFields(rs.getString("title"), rs.getString("content"),
                        rs.getString("round_level"), rs.getString("round_opinion"),
                        rs.getString("superior_correction")), emrId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==================================================================
    // 五、结构化差异
    // ==================================================================

    /**
     * 一个字段的差异。
     *
     * @param status       {@link #D_ADDED}（原本无内容→有）/ {@link #D_REMOVED}（有→无）/
     *                     {@link #D_MODIFIED} / {@link #D_UNCHANGED}
     * @param from         改前全文；<b>UNCHANGED 时为 null</b>（不重复回没变的内容）
     * @param to           改后全文；同上
     * @param firstDiffAt  首个不同字符的下标（0 起），UNCHANGED 时为 null。
     *                     按 Unicode 码点边界对齐，不会把一个代理对劈成两半
     * @param fromChanged  改前的差异片段（去掉公共前后缀），超 {@value #CHANGED_SEGMENT_MAX} 字截断并加省略号
     * @param toChanged    改后的差异片段，同上
     */
    public record FieldDiff(String field, String label, String status,
                            String from, String to, Integer fromLen, Integer toLen,
                            Integer firstDiffAt, String fromChanged, String toChanged) {

        public Map<String, Object> asMap() {
            var m = new LinkedHashMap<String, Object>();
            m.put("field", field);
            m.put("label", label);
            m.put("status", status);
            m.put("from", from);
            m.put("to", to);
            m.put("fromLen", fromLen);
            m.put("toLen", toLen);
            m.put("firstDiffAt", firstDiffAt);
            m.put("fromChanged", fromChanged);
            m.put("toChanged", toChanged);
            return m;
        }
    }

    /** 两份字段集的逐字段差异，按该病历类型的临床阅读顺序排列（不是字典序——字典序读起来是乱的）。 */
    public static List<FieldDiff> diff(String emrType, Map<String, String> from, Map<String, String> to) {
        var out = new ArrayList<FieldDiff>();
        for (String f : fieldOrder(emrType, from, to)) {
            String a = from == null ? null : from.get(f);
            String b = to == null ? null : to.get(f);
            String label = LABELS.getOrDefault(f, f);
            boolean aEmpty = a == null || a.isEmpty();
            boolean bEmpty = b == null || b.isEmpty();
            if (java.util.Objects.equals(a, b)) {
                out.add(new FieldDiff(f, label, D_UNCHANGED, null, null,
                        a == null ? null : a.length(), b == null ? null : b.length(), null, null, null));
                continue;
            }
            String status = aEmpty ? D_ADDED : bEmpty ? D_REMOVED : D_MODIFIED;
            String sa = a == null ? "" : a;
            String sb = b == null ? "" : b;
            int p = commonPrefix(sa, sb);
            int s = commonSuffix(sa, sb, p);
            out.add(new FieldDiff(f, label, status, a, b,
                    a == null ? null : a.length(), b == null ? null : b.length(),
                    p,
                    clip(sa.substring(p, sa.length() - s)),
                    clip(sb.substring(p, sb.length() - s))));
        }
        return out;
    }

    /** 已知字段按临床顺序在前，调用方多传的未知键按字典序排在后面（不丢，也不打乱主序）。 */
    private static List<String> fieldOrder(String emrType, Map<String, String> a, Map<String, String> b) {
        var order = new LinkedHashSet<>(INP.equals(emrType) ? INP_FIELDS : OUTP_FIELDS);
        var extra = new TreeMap<String, Boolean>();
        if (a != null) {
            a.keySet().forEach(k -> extra.put(k, true));
        }
        if (b != null) {
            b.keySet().forEach(k -> extra.put(k, true));
        }
        extra.keySet().forEach(order::add);
        return List.copyOf(order);
    }

    /**
     * 公共前缀长度，<b>按代理对边界对齐</b>：直接按 char 切会把一个 emoji 劈成半个代理对，
     * 拼回去就是乱码 U+FFFD——差异片段本身是要给人看的，不能自己先坏掉。
     */
    private static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        if (i > 0 && Character.isHighSurrogate(a.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    private static int commonSuffix(String a, String b, int prefix) {
        int max = Math.min(a.length(), b.length()) - prefix;
        int i = 0;
        while (i < max && a.charAt(a.length() - 1 - i) == b.charAt(b.length() - 1 - i)) {
            i++;
        }
        if (i > 0 && Character.isLowSurrogate(a.charAt(a.length() - i))) {
            i--;
        }
        return i;
    }

    private static String clip(String s) {
        if (s == null || s.length() <= CHANGED_SEGMENT_MAX) {
            return s;
        }
        return s.substring(0, CHANGED_SEGMENT_MAX) + "…（差异片段已截断，共 " + s.length() + " 字，全文见 from/to）";
    }

    private Map<String, Object> diffBody(String emrType, Long emrId, Snapshot a, Snapshot b) {
        var diffs = diff(emrType, a.fields(), b.fields());
        var changed = new ArrayList<String>();
        int added = 0;
        int removed = 0;
        int modified = 0;
        int unchanged = 0;
        for (var d : diffs) {
            switch (d.status()) {
                case D_ADDED -> {
                    added++;
                    changed.add(d.field());
                }
                case D_REMOVED -> {
                    removed++;
                    changed.add(d.field());
                }
                case D_MODIFIED -> {
                    modified++;
                    changed.add(d.field());
                }
                default -> unchanged++;
            }
        }
        var summary = new LinkedHashMap<String, Object>();
        summary.put("added", added);
        summary.put("removed", removed);
        summary.put("modified", modified);
        summary.put("unchanged", unchanged);
        summary.put("changedFields", List.copyOf(changed));
        summary.put("identical", changed.isEmpty());

        var body = new LinkedHashMap<String, Object>();
        body.put("emrType", emrType);
        body.put("emrId", emrId);
        body.put("from", snapshotMap(emrType, a));
        body.put("to", snapshotMap(emrType, b));
        body.put("summary", summary);
        body.put("diffs", diffs.stream().map(FieldDiff::asMap).toList());
        return body;
    }

    private static Map<String, Object> metaMap(VersionMeta m) {
        var o = new LinkedHashMap<String, Object>();
        o.put("versionId", m.id());
        o.put("versionNo", m.versionNo() == 0 ? null : m.versionNo());
        o.put("source", m.source());
        o.put("contentLen", m.contentLen());
        o.put("contentHash", m.contentHash());
        o.put("savedBy", m.savedBy());
        o.put("savedByName", m.savedByName());
        o.put("savedAt", m.savedAt());
        o.put("savedOn", m.savedOn());
        return o;
    }

    private static Map<String, Object> snapshotMap(String emrType, Snapshot s) {
        var o = metaMap(s.meta());
        o.put("label", s.label());
        o.put("content", s.content());
        // fields 按临床阅读顺序回，不按 content 里的字典序——存的是规范序，看的是阅读序
        var ordered = new LinkedHashMap<String, Object>();
        for (String f : fieldOrder(emrType, s.fields(), null)) {
            var one = new LinkedHashMap<String, Object>();
            one.put("label", LABELS.getOrDefault(f, f));
            one.put("value", s.fields().get(f));
            ordered.put(f, one);
        }
        o.put("fields", ordered);
        return o;
    }

    // ==================================================================
    // 六、配置与运行状况
    // ==================================================================

    /**
     * 当前生效的配置与运行计数。
     *
     * <p><b>gate=off 时把「合规声明失效」这句话明说出来</b>：一个开关能静默关掉法定留痕，
     * 而任何人都看不出来它关着——这才是真正危险的地方。
     */
    public Map<String, Object> settings() {
        String gate = gate();
        var body = new LinkedHashMap<String, Object>();
        body.put("gate", gate);
        body.put("gateKey", GATE_KEY);
        body.put("dedup", dedup());
        body.put("dedupKey", DEDUP_KEY);
        body.put("maxContentChars", maxContentChars());
        body.put("maxContentCharsKey", MAX_CHARS_KEY);
        body.put("complianceClaimHolds", !"off".equals(gate));
        body.put("emrTypes", List.of(OUTP, INP));
        body.put("sources", List.of(MANUAL, AUTO, SUBMIT));
        body.put("outpFields", OUTP_FIELDS);
        body.put("inpFields", INP_FIELDS);

        var counters = new LinkedHashMap<String, Object>();
        counters.put("recorded", recordedCount.get());
        counters.put("deduped", dedupedCount.get());
        counters.put("failed", failedCount.get());
        counters.put("skipped", skippedCount.get());
        counters.put("lastFailure", lastFailure);
        counters.put("lastFailureAt", lastFailureAt);
        counters.put("note", "进程内计数，重启归零。failed > 0 表示 warn 档吞掉过留痕失败——"
                + "医生的病历存进去了，但那一版没有痕迹，须查 ERROR 日志 [emr-version]");
        body.put("counters", counters);

        var notes = new ArrayList<String>();
        notes.add("只回看不回滚：不提供「恢复到某一版」，回滚会让当前正文的责任人变得不清楚");
        notes.add("版本不可篡改：没有任何修改/删除版本的端点");
        notes.add("零回填：版本留痕上线前的病历没有历史版本，本版不伪造初版");
        if ("off".equals(gate)) {
            notes.add("【当前 gate=off】完全不落版本，本版「病历修改留痕可追溯」的合规声明在这一档位下不成立。"
                    + "这是逃生口，不应作为常态配置。");
        }
        if ("block".equals(gate)) {
            notes.add("【当前 gate=block】留痕表出问题时医生将无法保存病历（5705）——这是这一档位的既定代价。");
        }
        body.put("notes", notes);
        return body;
    }

    // ==================================================================
    // 七、公共校验
    // ==================================================================

    private static Result checkTarget(String emrType, Long emrId) {
        if (!OUTP.equals(emrType) && !INP.equals(emrType)) {
            return err(5700, "病历类型非法：" + emrType + "（只接受 OUTP / INP）");
        }
        if (emrId == null || emrId <= 0) {
            return err(5701, "病历 id 非法：" + emrId);
        }
        return null;
    }

    /** 规范字段集（给主控与前端确认口径用；返回不可变副本）。 */
    public static List<String> fieldsOf(String emrType) {
        return INP.equals(emrType) ? INP_FIELDS : OUTP_FIELDS;
    }

    /** 字段中文名（未知字段回自身）。 */
    public static String labelOf(String field) {
        return LABELS.getOrDefault(field, field);
    }

    static {
        // 防呆：两个字段集不得有交集之外的重名歧义（将来有人加字段时早点炸在启动期，而不是差异页上）
        var dup = new ArrayList<>(OUTP_FIELDS);
        dup.retainAll(INP_FIELDS);
        if (!dup.isEmpty()) {
            throw new IllegalStateException("门诊/住院字段集重名会让 LABELS 语义冲突：" + dup);
        }
    }
}
