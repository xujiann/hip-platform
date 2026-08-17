package cn.hip.medtech.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** 二十九期：云胶片/报告对外分享——短链 token 匿名访问，限时失效，姓名脱敏 */
@RestController
@RequiredArgsConstructor
public class ReportShareController {

    private final JdbcTemplate jdbc;

    /** 有效期上限 30 天：曾可传 ?expireMinutes=52560000 造出百年外链（1.1.4 B-10） */
    private static final int MAX_EXPIRE_MINUTES = 30 * 24 * 60;

    /** 生成分享链接（默认 72 小时有效，上限 30 天） */
    @PostMapping("/api/ris/exams/{id}/share")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN','DOCTOR_OUTP')")
    @Transactional
    public R<Map<String, Object>> share(@PathVariable Long id,
                                        @RequestParam(defaultValue = "4320") int expireMinutes) {
        if (expireMinutes <= 0 || expireMinutes > MAX_EXPIRE_MINUTES) {
            return R.fail(4663, "有效期须为 1 分钟至 30 天");
        }
        Integer verified = jdbc.queryForObject(
                "select count(*) from ris_exam where id = ? and status = 'VERIFIED'", Integer.class, id);
        if (verified == null || verified == 0) return R.fail(4660, "报告不存在或未审核发布");
        String token = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                insert into report_share(token, exam_id, expire_at)
                values (?,?, now() + make_interval(mins => ?))
                """, token, id, expireMinutes);
        return R.ok(Map.of("token", token, "url", "/api/share/" + token, "expireMinutes", expireMinutes));
    }

    /** 吊销：链接泄漏后的唯一回收手段——直接置为已过期（B-10，此前无任何 revoke 端点） */
    @DeleteMapping("/api/ris/exams/{id}/share")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN','DOCTOR_OUTP')")
    @Transactional
    public R<Map<String, Object>> revoke(@PathVariable Long id) {
        int n = jdbc.update(
                "update report_share set expire_at = now() where exam_id = ? and expire_at > now()", id);
        return R.ok(Map.of("revoked", n));
    }

    /** 匿名访问分享报告（permitAll）：过期即失效，姓名脱敏 */
    @GetMapping("/api/share/{token}")
    @Transactional
    public R<Map<String, Object>> view(@PathVariable String token) {
        var rows = jdbc.queryForList("""
                select s.id as share_id, s.expire_at < now() as expired, e.findings, e.impression, e.verified_at,
                       o.item_name, p.name as patient_name, e.modality
                from report_share s
                join ris_exam e on e.id = s.exam_id
                join outp_order o on o.id = e.order_id
                join outp_registration r on r.id = o.registration_id
                join empi_patient p on p.id = r.patient_id
                where s.token = ?
                """, token);
        if (rows.isEmpty()) return R.fail(4661, "分享链接不存在");
        var row = rows.get(0);
        if (Boolean.TRUE.equals(row.get("expired"))) return R.fail(4662, "分享链接已过期");
        jdbc.update("update report_share set access_cnt = access_cnt + 1 where id = ?", row.get("share_id"));
        String name = (String) row.get("patient_name");
        return R.ok(Map.of(
                "patientName", cn.hip.platform.core.common.Masking.name(name),
                "itemName", row.get("item_name"),
                "modality", row.get("modality"),
                "findings", row.get("findings"),
                "impression", row.get("impression"),
                "verifiedAt", String.valueOf(row.get("verified_at"))));
    }
}
