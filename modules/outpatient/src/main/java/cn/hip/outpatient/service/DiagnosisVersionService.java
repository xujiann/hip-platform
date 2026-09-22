package cn.hip.outpatient.service;

import cn.hip.outpatient.entity.OutpDiagnosis;
import cn.hip.platform.core.service.ConfigReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        if (registrationId == null || diagnoses == null || diagnoses.isEmpty()) {
            // 一条诊断都没有不算一次「修改」：开单未写诊断是常态，为它落版本只是噪音。
            return false;
        }
        String gate = configReader.get(GATE_KEY, "warn");
        if ("off".equalsIgnoreCase(gate)) {
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
