package cn.hip.outpatient.service;

import cn.hip.outpatient.entity.OutpDiagnosis;
import cn.hip.platform.core.service.ConfigReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 门诊诊断修改留痕（v74）。
 *
 * <p><b>为什么单独一张表而不并进病历版本</b>：病历版本的门诊字段集与 CA 签名口径严格对齐
 * （签的就是那五段正文），并进诊断会造成「快照里有、签名没覆盖」的错位；而给多态版本表加
 * 第三个类型又放不下列宽、且会与 {@code emr_amendment} 共用的枚举分叉。详见 V170 的表注释。
 *
 * <p><b>口径与病历版本保持一致，刻意不另立一套</b>：
 * 序列化与哈希直接复用 {@link EmrVersionService#canonicalJson}/{@link EmrVersionService#sha256Hex}，
 * 开关复用 {@code emr.version.gate}，快照的是保存后的新状态，同内容不落新版。
 *
 * <p><b>留痕失败不阻断保存</b>：与病历版本接缝同口径——写不进去只记 ERROR 日志，
 * 不让医生因为留痕坏了而存不上病历。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosisVersionService {

    /** 与病历版本共用同一个开关：两者都是「病历修改留痕」，不另立配置键。 */
    private static final String GATE_KEY = EmrVersionService.GATE_KEY;

    /** 建议锁命名空间。与病历版本的 5700/5701 错开。 */
    private static final int LOCK_NS_DIAG = 5702;

    private final JdbcTemplate jdbc;
    private final ConfigReader configReader;

    /**
     * 记录一次诊断快照。<b>在诊断落库之后调用</b>——快照的是保存后的新状态。
     *
     * @return true = 落了新版；false = 去重跳过 / 开关关闭 / 无诊断 / 写入失败
     */
    @Transactional
    public boolean record(Long registrationId, List<OutpDiagnosis> diagnoses, Long userId) {
        if (registrationId == null || diagnoses == null) {
            return false;
        }
        String gate = configReader.get(GATE_KEY, "warn");
        if ("off".equalsIgnoreCase(gate)) {
            return false;
        }
        if (diagnoses.isEmpty() && lastVersionNo(registrationId) == null) {
            // 从没写过诊断时的「空」不算一次修改：开单未写诊断是常态，为它落版本只是噪音。
            // **但已有过诊断版本后的「空」是修改**（v79）：saveEmr 是删重插，空集合即把诊断删光，
            // 此前这里一律返回，「把 A 删掉」这件事在留痕里一个字都没有。下面照常落一版空快照（同内容去重照旧）。
            return false;
        }
        // **savepoint 是「留痕失败不连累保存」的全部技术依据**（照抄 EmrVersionService 的处置）：
        // PG 里任何一条语句失败即把整个事务判 aborted，Java 层 catch 住也救不回来——
        // 不开 savepoint 的话，留痕一出错医生的病历就一起存不进去。
        // 本轮用例当场抓到过这一点：漏了 savepoint，三条用例连病历保存都跟着红了。
        boolean tx = org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive();
        String sp = "hip_diag_ver_" + System.nanoTime();
        if (tx) {
            jdbc.execute("savepoint " + sp);
        }
        try {
            String content = EmrVersionService.canonicalJson(snapshot(diagnoses));
            String hash = EmrVersionService.sha256Hex(content);

            // 同内容不落新版。不去重的话，医生每存一次正文就刷一条诊断版本，
            // 真正的诊断修改会被淹掉——那不是留痕，是噪音。
            String lastHash = jdbc.query(
                    "select content_hash from outp_diagnosis_version where registration_id = ? "
                            + "order by version_no desc limit 1",
                    rs -> rs.next() ? rs.getString(1) : null, registrationId);
            if (hash.equals(lastHash)) {
                return false;
            }

            // 并发两次保存同一就诊时串行化取号；unique 约束是最后一道防线。
            // 用 ResultSetExtractor 而非 update/queryForObject：该函数返回 void（OID 2278）。
            // 第二参是 int4，registrationId 是 bigint——取模收窄，撞号只会多串行化一点，不影响正确性。
            jdbc.query("select pg_advisory_xact_lock(?, ?)",
                    (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                    LOCK_NS_DIAG, (int) (registrationId % Integer.MAX_VALUE));
            Integer next = jdbc.queryForObject(
                    "select coalesce(max(version_no), 0) + 1 from outp_diagnosis_version "
                            + "where registration_id = ?", Integer.class, registrationId);
            jdbc.update("""
                    insert into outp_diagnosis_version
                        (registration_id, version_no, content, content_hash, changed_by)
                    values (?, ?, ?, ?, ?)
                    """, registrationId, next == null ? 1 : next, content, hash, userId);
            if (tx) {
                jdbc.execute("release savepoint " + sp);
            }
            return true;
        } catch (RuntimeException e) {
            if (tx) {
                jdbc.execute("rollback to savepoint " + sp);
            }
            // 与病历版本接缝同口径：留痕失败不连累保存，但必须留下 ERROR 让运维看得见。
            log.error("[diag-version] 诊断留痕失败 registrationId={}：{}", registrationId, e.getMessage(), e);
            return false;
        }
    }

    private Integer lastVersionNo(Long registrationId) {
        return jdbc.query(
                "select max(version_no) from outp_diagnosis_version where registration_id = ?",
                rs -> rs.next() ? (Integer) rs.getObject(1) : null, registrationId);
    }

    // ==================================================================
    // v79（994★）：只读读出。此前本类只有 record()，全仓没有任何查询端点——留痕读不出来等于没留。
    // ==================================================================

    /**
     * 某次门诊就诊的诊断历次版本，<b>按版本倒序</b>（最新在上）。
     *
     * <p>每版给：版本序、操作人（id 与姓名）、时间、该版诊断明细（主诊断/前缀/后缀/疑诊/诊断体系/临床描述，
     * 照写入时的快照原样拆回，不拿当前诊断表补）、与上一版相比加了哪几条/去了哪几条/主诊断换没换。
     * 一次就诊的诊断版本只有个位数，不分页。
     *
     * <p>错误码复用：id 非法 4000、挂号不存在 4001（v79 规划节：本轮不发新码）。
     */
    public EmrVersionService.Result list(Long registrationId) {
        if (registrationId == null || registrationId <= 0) {
            return new EmrVersionService.Result(4000, "挂号 id 非法：" + registrationId, null);
        }
        Integer exists = jdbc.queryForObject(
                "select count(*) from outp_registration where id = ?", Integer.class, registrationId);
        if (exists == null || exists == 0) {
            return new EmrVersionService.Result(4001, "挂号记录不存在：" + registrationId, null);
        }
        var rows = jdbc.queryForList("""
                select v.version_no, v.content, v.changed_by, u.real_name as changed_by_name, v.changed_at
                from outp_diagnosis_version v
                left join sys_user u on u.id = v.changed_by
                where v.registration_id = ?
                order by v.version_no
                """, registrationId);

        var asc = new ArrayList<Map<String, Object>>(rows.size());
        List<Map<String, Object>> prev = null;
        for (var r : rows) {
            List<Map<String, Object>> cur = unsnapshot(EmrVersionService.parseFields(String.valueOf(r.get("content"))));
            var item = new LinkedHashMap<String, Object>();
            item.put("registrationId", registrationId);
            item.put("versionNo", ((Number) r.get("version_no")).intValue());
            Object by = r.get("changed_by");
            item.put("changedBy", by == null ? null : ((Number) by).longValue());
            item.put("changedByName", r.get("changed_by_name"));
            Object at = r.get("changed_at");
            item.put("changedAt", at instanceof java.sql.Timestamp ts ? ts.toInstant() : at);
            item.put("diagnoses", cur);
            item.put("changes", prev == null ? null : changes(prev, cur));
            asc.add(item);
            prev = cur;
        }
        var items = new ArrayList<>(asc);
        Collections.reverse(items);

        String gate = configReader.get(GATE_KEY, "warn");
        var body = new LinkedHashMap<String, Object>();
        body.put("registrationId", registrationId);
        body.put("total", items.size());
        body.put("gate", gate);
        body.put("items", items);
        if (items.isEmpty()) {
            // v68 复核打回点的同一处置：**只陈述事实与可求值的条件，不断言原因**。
            // 零版本可能因为从没录过诊断、gate=off 时保存、留痕写入失败（warn 档只记日志），本端点分不出是哪一种。
            body.put("notice", emptyNotice(gate));
        }
        body.put("notes", List.of(
                "诊断版本在保存病历时写入，且只在诊断与上一版不同时才记一版；签名、补正不改诊断，不产生诊断版本",
                "每版是保存后的完整诊断快照（第一条为主诊断），不是差异；「较上一版」由相邻两版逐条比出",
                "操作人为空表示那次保存没有登录上下文，如实回空，不做回填猜测"));
        return new EmrVersionService.Result(0, "success", body);
    }

    static String emptyNotice(String gate) {
        var sb = new StringBuilder("本次就诊暂无诊断版本记录。");
        if ("off".equalsIgnoreCase(gate)) {
            sb.append("当前留痕档位 ").append(GATE_KEY).append("=off：此刻保存病历不会写入诊断版本。");
        }
        sb.append("诊断版本只在保存病历、且诊断与上一版不同时写入；首次保存时一条诊断都没有的不记。")
                .append("就诊存在而没有诊断版本的情形不止一种（例如从未录入诊断、留痕档位为 off 时保存、留痕写入失败），")
                .append("本页不断言是哪一种。");
        return sb.toString();
    }

    /** 快照扁平键 {@code d1.icdName …} → 按序号还原的诊断列表。规范 JSON 的键是字典序（d10 排在 d2 前），故按序号重排。 */
    private static List<Map<String, Object>> unsnapshot(Map<String, String> flat) {
        var byIdx = new TreeMap<Integer, Map<String, String>>();
        for (var e : flat.entrySet()) {
            String k = e.getKey();
            int dot = k.indexOf('.');
            if (!k.startsWith("d") || dot < 2) {
                continue;
            }
            int idx;
            try {
                idx = Integer.parseInt(k.substring(1, dot));
            } catch (NumberFormatException ex) {
                continue;
            }
            byIdx.computeIfAbsent(idx, i -> new LinkedHashMap<>()).put(k.substring(dot + 1), e.getValue());
        }
        var out = new ArrayList<Map<String, Object>>(byIdx.size());
        for (var e : byIdx.entrySet()) {
            var raw = e.getValue();
            var d = new LinkedHashMap<String, Object>();
            d.put("seq", e.getKey());
            d.put("primary", Boolean.parseBoolean(raw.get("primary")));
            for (String f : SNAPSHOT_FIELDS) {
                d.put(f, raw.get(f));
            }
            d.put("display", display(raw));
            out.add(d);
        }
        return out;
    }

    private static final List<String> SNAPSHOT_FIELDS = List.of(
            "icdCode", "icdName", "customName", "prefix", "suffix", "certainty", "diagSystem");

    /** 一条诊断照快照拼成的可读全貌：比对与上屏同用这一个串，免得「点名的」与「列出的」是两套口径。 */
    static String display(Map<String, String> d) {
        var sb = new StringBuilder();
        if (OutpDiagnosis.SYSTEM_TCM.equals(d.get("diagSystem"))) {
            sb.append("【中医】");
        }
        sb.append(nz(d.get("prefix"))).append(nz(d.get("icdName"))).append(nz(d.get("suffix")));
        String custom = nz(d.get("customName"));
        if (!custom.isEmpty() && !custom.equals(nz(d.get("icdName")))) {
            sb.append("〔").append(custom).append("〕");
        }
        if (OutpDiagnosis.CERTAINTY_SUSPECTED.equals(d.get("certainty"))) {
            sb.append("（疑诊）");
        } else if (OutpDiagnosis.CERTAINTY_CONFIRMED.equals(d.get("certainty"))) {
            sb.append("（确诊）");
        }
        String code = nz(d.get("icdCode"));
        if (!code.isEmpty()) {
            sb.append(' ').append(code);
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    /** 相邻两版逐条比：按 display 全貌做多重集差，主诊断单独比（主诊断换人也是一次修改）。 */
    private static Map<String, Object> changes(List<Map<String, Object>> prev, List<Map<String, Object>> cur) {
        var prevAll = new ArrayList<String>();
        prev.forEach(d -> prevAll.add((String) d.get("display")));
        var curAll = new ArrayList<String>();
        cur.forEach(d -> curAll.add((String) d.get("display")));
        var removed = new ArrayList<>(prevAll);
        curAll.forEach(removed::remove);
        var added = new ArrayList<>(curAll);
        prevAll.forEach(added::remove);
        Object prevPrimary = prev.isEmpty() ? null : prev.get(0).get("display");
        Object curPrimary = cur.isEmpty() ? null : cur.get(0).get("display");
        var m = new LinkedHashMap<String, Object>();
        m.put("added", added);
        m.put("removed", removed);
        m.put("primaryChanged", !Objects.equals(prevPrimary, curPrimary));
        return m;
    }

    /**
     * 诊断集合 → 规范字段集。按序号展开成扁平键，让两版之间的差异能逐条对出来
     * （直接塞一个 JSON 数组字符串的话，差异只能看出「整串变了」）。
     *
     * <p>带上 {@code primary}：主诊断换人也是一次真实修改，必须被快照捕捉到。
     */
    private static Map<String, String> snapshot(List<OutpDiagnosis> diagnoses) {
        var m = new LinkedHashMap<String, String>();
        for (int i = 0; i < diagnoses.size(); i++) {
            OutpDiagnosis d = diagnoses.get(i);
            String p = "d" + (i + 1) + ".";
            m.put(p + "icdCode", d.getIcdCode());
            m.put(p + "icdName", d.getIcdName());
            m.put(p + "customName", d.getCustomName());
            m.put(p + "prefix", d.getPrefix());
            m.put(p + "suffix", d.getSuffix());
            m.put(p + "certainty", d.getCertainty());
            m.put(p + "diagSystem", d.getDiagSystem());
            m.put(p + "primary", String.valueOf(i == 0));
        }
        return m;
    }
}
