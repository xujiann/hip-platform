package cn.hip.medtech.web;

import cn.hip.platform.core.common.Masking;
import cn.hip.platform.core.common.R;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 限时脱敏报告外链。与报告生产者相同锁序 O -> E -> share，锁后裁决发布事实及实际有效期。 */
@RestController
@RequiredArgsConstructor
public class ReportShareController {
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private static final int MAX_EXPIRE_MINUTES = 30 * 24 * 60;

    @PostMapping("/api/ris/exams/{id}/share")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN','DOCTOR_OUTP')")
    @Transactional
    public R<Map<String, Object>> share(@PathVariable Long id,
                                        @RequestParam(defaultValue = "4320") int expireMinutes) {
        operator();
        if (expireMinutes <= 0 || expireMinutes > MAX_EXPIRE_MINUTES)
            return R.fail(4663, "有效期须为 1 分钟至 30 天");
        var locked = lockExam(id, true);
        if (locked == null || !locked.publishable()) return unavailable();
        String token = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                insert into report_share(token, exam_id, expire_at)
                values (?,?, clock_timestamp() + make_interval(mins => ?))
                """, token, id, expireMinutes);
        return R.ok(Map.of("token", token, "url", "/api/share/" + token, "expireMinutes", expireMinutes));
    }

    /** 失效源或已撤回报告仍可吊销善后；不要求当前可发布。 */
    @DeleteMapping("/api/ris/exams/{id}/share")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN','DOCTOR_OUTP')")
    @Transactional
    public R<Map<String, Object>> revoke(@PathVariable Long id) {
        operator();
        var locked = lockExam(id, true);
        if (locked == null) return R.ok(Map.of("revoked", 0));
        jdbc.queryForList("select id from report_share where exam_id=? order by id for update", id);
        operator();
        int n = jdbc.update("""
                update report_share set expire_at = clock_timestamp()
                where exam_id = ? and expire_at > clock_timestamp()
                """, id);
        return R.ok(Map.of("revoked", n));
    }

    /** permitAll 仅允许有效短链读取当前仍可发布的原对象；拒绝不计成功访问。 */
    @GetMapping("/api/share/{token}")
    @Transactional
    public R<Map<String, Object>> view(@PathVariable String token) {
        entityManager.flush();
        var refs = jdbc.queryForList("select id,exam_id from report_share where token=?", token);
        if (refs.isEmpty()) return missing();
        var ref = refs.getFirst();
        Long examId = ((Number) ref.get("exam_id")).longValue();
        var locked = lockExam(examId, false);
        if (locked == null) return missing();
        var links = jdbc.queryForList("""
                select id from report_share where id=? and token=? and exam_id=? for update
                """, ref.get("id"), token, examId);
        if (links.isEmpty()) return missing();
        // now() is the transaction start: a token can expire while waiting for O, E or share.
        Boolean active = jdbc.queryForObject("select expire_at > clock_timestamp() from report_share where id=?",
                Boolean.class, ref.get("id"));
        if (!Boolean.TRUE.equals(active)) return R.fail(4662, "分享链接已过期");
        if (!locked.publishable()) return unavailable();
        var people = jdbc.queryForList("""
                select o.item_name,p.name as patient_name from outp_order o
                join outp_registration r on r.id=o.registration_id
                join empi_patient p on p.id=r.patient_id where o.id=?
                """, locked.exam().get("order_id"));
        if (people.isEmpty()) return unavailable();
        var person = people.getFirst();
        var exam = locked.exam();
        var out = new LinkedHashMap<String, Object>();
        out.put("patientName", Masking.name((String) person.get("patient_name")));
        out.put("itemName", person.get("item_name"));
        out.put("modality", exam.get("modality"));
        out.put("findings", exam.get("findings"));
        out.put("impression", exam.get("impression"));
        out.put("verifiedAt", String.valueOf(exam.get("verified_at")));
        jdbc.update("update report_share set access_cnt=access_cnt+1 where id=?", ref.get("id"));
        return R.ok(out);
    }

    private record LockedExam(Map<String, Object> exam, boolean publishable) {}

    /** No E lock is held while waiting for O. An in-flight request never follows an E rebind. */
    private LockedExam lockExam(Long id, boolean staff) {
        if (staff) operator();
        entityManager.flush();
        var refs = jdbc.queryForList("select order_id from ris_exam where id=?", Long.class, id);
        if (refs.isEmpty()) return null;
        Long orderId = refs.getFirst();
        var orders = jdbc.queryForList("select id,order_type,status from outp_order where id=? for update", orderId);
        if (staff) operator();
        var exams = jdbc.queryForList("select * from ris_exam where id=? for update", id);
        if (staff) operator();
        if (orders.isEmpty() || exams.isEmpty() || !Objects.equals(orderId, exams.getFirst().get("order_id"))) return null;
        var order = orders.getFirst();
        var exam = exams.getFirst();
        boolean publishable = "EXAM".equals(order.get("order_type"))
                && Set.of("CHARGED", "EXECUTED").contains(String.valueOf(order.get("status")))
                && "VERIFIED".equals(exam.get("status"));
        return new LockedExam(exam, publishable);
    }

    private void operator() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities().stream().noneMatch(a ->
                Set.of("ROLE_ADMIN", "ROLE_TECHNICIAN", "ROLE_DOCTOR_OUTP").contains(a.getAuthority())))
            throw new AccessDeniedException("无报告分享权限");
        var ids = jdbc.queryForList("select id from sys_user where username=? and enabled=true", Long.class, auth.getName());
        if (ids.isEmpty()) throw new AccessDeniedException("报告分享操作人不存在或已停用");
    }

    private R<Map<String, Object>> missing() { return R.fail(4661, "分享链接不存在或关联已变化"); }
    private R<Map<String, Object>> unavailable() { return R.fail(4660, "报告不存在、未审核发布或源医嘱已失效"); }
}
