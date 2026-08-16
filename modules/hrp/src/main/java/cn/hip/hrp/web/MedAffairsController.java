package cn.hip.hrp.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 二十五期：医务管理台账——医师定期考核/处方权/手术授权等资质，到期预警 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/hrp/credentials")
@PreAuthorize("hasAnyRole('ADMIN','QUALITY')")   // 1.0.9：权限清点补齐
public class MedAffairsController {

    private final JdbcTemplate jdbc;

    public record CredentialReq(String staffName, String certType, String certNo,
                                String issuedAt, String expireDate, String note) {}

    @PostMapping
    public R<Void> add(@RequestBody CredentialReq req) {
        if (req.staffName() == null || req.expireDate() == null) return R.fail(4590, "姓名与到期日必填");
        jdbc.update("""
                insert into med_staff_credential(staff_name, cert_type, cert_no, issued_at, expire_date, note)
                values (?,?,?,?::date,?::date,?)
                """, req.staffName(), req.certType(), req.certNo(), req.issuedAt(), req.expireDate(), req.note());
        return R.ok();
    }

    /** 台账（含到期状态：EXPIRED / EXPIRING 90 天内 / VALID） */
    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(jdbc.queryForList("""
                select *, case when expire_date < current_date then 'EXPIRED'
                               when expire_date < current_date + 90 then 'EXPIRING'
                               else 'VALID' end as validity
                from med_staff_credential order by expire_date
                """));
    }

    /** 到期预警：90 天内到期或已过期 */
    @GetMapping("/expiring")
    public R<List<Map<String, Object>>> expiring() {
        return R.ok(jdbc.queryForList("""
                select * from med_staff_credential
                where expire_date < current_date + 90 order by expire_date
                """));
    }
}
