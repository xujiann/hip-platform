package cn.hip.inpatient.service;

import cn.hip.platform.core.service.ConfigReader;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * v35：住院病历完整性检查（出院/归档 gate 与只读预检共用）。
 *
 * <p>返回中文缺项清单（空=完整）。纯只读，无副作用、不 throw——挡/放由各挡点按 sys_config
 * 三态（off|warn|block，默认 warn）决定。判定口径与病案首页/时限报表一致：入院记录 / 出院小结 /
 * 病程数阈值 / 出院小结 CA 签名 / 手术病例（手术记录+术前小结+手术知情同意）。
 *
 * <p>v42 车道3：新增 {@link #checkDetailed(Long)} 返回**带项目码**的结构体，供病案终末质控
 * 评分单按 {@code mr_qc_item.auto_rule} 自动预填扣分明细。判定逻辑统一收在 {@link #findings(Long)}，
 * {@link #check(Long)} 由它 map 出中文文案——<b>既有 {@code List<String>} 返回类型与每一条
 * 中文文案逐字节不变</b>（3 处生产调用 + EmrIntegrityGateTest 断言 + 前端 tooltip + E2E 契约都消费它）。
 */
@Service
@RequiredArgsConstructor
public class EmrIntegrityService {

    private final JdbcTemplate jdbc;
    private final ConfigReader configReader;

    /**
     * 一条完整性缺项：{@code code} 稳定机读码（与 mr_qc_item.auto_rule 对齐，不随文案调整而变），
     * {@code text} 面向用户的中文文案（与 {@link #check(Long)} 逐字一致）。
     */
    public record Finding(String code, String text) {}

    /** 缺项码：新增码只能追加，既有码不得改名——评分单字典 auto_rule 按码绑定 */
    public static final String CODE_MISS_ADMISSION = "MISS_ADMISSION";
    public static final String CODE_MISS_DISCHARGE = "MISS_DISCHARGE";
    public static final String CODE_DISCHARGE_UNSIGNED = "DISCHARGE_UNSIGNED";
    public static final String CODE_PROGRESS_INSUFFICIENT = "PROGRESS_INSUFFICIENT";
    public static final String CODE_MISS_OP_NOTE = "MISS_OP_NOTE";
    public static final String CODE_MISS_PREOP = "MISS_PREOP";
    public static final String CODE_MISS_SURGERY_CONSENT = "MISS_SURGERY_CONSENT";

    public List<String> check(Long admissionId) {
        var found = findings(admissionId);
        List<String> missing = new ArrayList<>(found.size());
        for (Finding f : found) missing.add(f.text());
        return missing;
    }

    /**
     * v42：同 {@link #check(Long)} 的判定，返回带码结构（顺序一致）。
     *
     * <p>仅供终末质控评分单自动预填使用——<b>不挂任何 gate</b>，甲乙丙是事后管理评价，
     * 不是出院/归档准入条件。
     */
    public List<Finding> checkDetailed(Long admissionId) {
        return findings(admissionId);
    }

    /** 唯一判定入口：中文文案在此定死，check() 与 checkDetailed() 都由它派生，两者永不漂移 */
    private List<Finding> findings(Long admissionId) {
        List<Finding> missing = new ArrayList<>();

        Integer admission = jdbc.queryForObject(
                "select count(*) from inp_medical_record where admission_id = ? and record_type = 'ADMISSION'",
                Integer.class, admissionId);
        if (admission == null || admission == 0) missing.add(new Finding(CODE_MISS_ADMISSION, "缺入院记录"));

        Integer discharge = jdbc.queryForObject(
                "select count(*) from inp_medical_record where admission_id = ? and record_type = 'DISCHARGE'",
                Integer.class, admissionId);
        Integer dischargeSigned = jdbc.queryForObject(
                "select count(*) from inp_medical_record where admission_id = ? and record_type = 'DISCHARGE' and signature is not null",
                Integer.class, admissionId);
        if (discharge == null || discharge == 0) {
            missing.add(new Finding(CODE_MISS_DISCHARGE, "缺出院小结"));
        } else if (dischargeSigned == null || dischargeSigned == 0) {
            missing.add(new Finding(CODE_DISCHARGE_UNSIGNED, "出院小结未 CA 签名"));
        }

        int minProgress = configReader.getInt("emr.integrity.min_progress_notes", 1);
        Integer progress = jdbc.queryForObject("""
                select count(*) from inp_medical_record
                where admission_id = ? and record_type in ('PROGRESS', 'ROUND', 'FIRST_PROGRESS')
                """, Integer.class, admissionId);
        if (progress == null || progress < minProgress) {
            missing.add(new Finding(CODE_PROGRESS_INSUFFICIENT, "病程记录不足（少于 " + minProgress + " 条）"));
        }

        // 手术病例：有非作废手术即须手术记录 + 术前小结 + 手术知情同意
        Integer surgery = jdbc.queryForObject(
                "select count(*) from inp_surgery where admission_id = ? and status <> 'CANCELLED'",
                Integer.class, admissionId);
        if (surgery != null && surgery > 0) {
            Integer opNote = jdbc.queryForObject(
                    "select count(*) from inp_surgery where admission_id = ? and status = 'DONE' and op_note is not null",
                    Integer.class, admissionId);
            if (opNote == null || opNote == 0) missing.add(new Finding(CODE_MISS_OP_NOTE, "缺手术记录"));
            Integer preop = jdbc.queryForObject(
                    "select count(*) from inp_medical_record where admission_id = ? and record_type = 'PREOP'",
                    Integer.class, admissionId);
            if (preop == null || preop == 0) missing.add(new Finding(CODE_MISS_PREOP, "缺术前小结"));
            Integer consent = jdbc.queryForObject("""
                    select count(*) from emr_consent
                    where admission_id = ? and consent_type = 'SURGERY' and status = 'SIGNED' and revoked_at is null
                    """, Integer.class, admissionId);
            if (consent == null || consent == 0) missing.add(new Finding(CODE_MISS_SURGERY_CONSENT, "缺手术知情同意书"));
        }
        return missing;
    }
}
