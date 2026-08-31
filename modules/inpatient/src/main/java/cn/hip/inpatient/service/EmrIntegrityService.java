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
 */
@Service
@RequiredArgsConstructor
public class EmrIntegrityService {

    private final JdbcTemplate jdbc;
    private final ConfigReader configReader;

    public List<String> check(Long admissionId) {
        List<String> missing = new ArrayList<>();

        Integer admission = jdbc.queryForObject(
                "select count(*) from inp_medical_record where admission_id = ? and record_type = 'ADMISSION'",
                Integer.class, admissionId);
        if (admission == null || admission == 0) missing.add("缺入院记录");

        Integer discharge = jdbc.queryForObject(
                "select count(*) from inp_medical_record where admission_id = ? and record_type = 'DISCHARGE'",
                Integer.class, admissionId);
        Integer dischargeSigned = jdbc.queryForObject(
                "select count(*) from inp_medical_record where admission_id = ? and record_type = 'DISCHARGE' and signature is not null",
                Integer.class, admissionId);
        if (discharge == null || discharge == 0) {
            missing.add("缺出院小结");
        } else if (dischargeSigned == null || dischargeSigned == 0) {
            missing.add("出院小结未 CA 签名");
        }

        int minProgress = configReader.getInt("emr.integrity.min_progress_notes", 1);
        Integer progress = jdbc.queryForObject("""
                select count(*) from inp_medical_record
                where admission_id = ? and record_type in ('PROGRESS', 'ROUND', 'FIRST_PROGRESS')
                """, Integer.class, admissionId);
        if (progress == null || progress < minProgress) missing.add("病程记录不足（少于 " + minProgress + " 条）");

        // 手术病例：有非作废手术即须手术记录 + 术前小结 + 手术知情同意
        Integer surgery = jdbc.queryForObject(
                "select count(*) from inp_surgery where admission_id = ? and status <> 'CANCELLED'",
                Integer.class, admissionId);
        if (surgery != null && surgery > 0) {
            Integer opNote = jdbc.queryForObject(
                    "select count(*) from inp_surgery where admission_id = ? and status = 'DONE' and op_note is not null",
                    Integer.class, admissionId);
            if (opNote == null || opNote == 0) missing.add("缺手术记录");
            Integer preop = jdbc.queryForObject(
                    "select count(*) from inp_medical_record where admission_id = ? and record_type = 'PREOP'",
                    Integer.class, admissionId);
            if (preop == null || preop == 0) missing.add("缺术前小结");
            Integer consent = jdbc.queryForObject("""
                    select count(*) from emr_consent
                    where admission_id = ? and consent_type = 'SURGERY' and status = 'SIGNED' and revoked_at is null
                    """, Integer.class, admissionId);
            if (consent == null || consent == 0) missing.add("缺手术知情同意书");
        }
        return missing;
    }
}
