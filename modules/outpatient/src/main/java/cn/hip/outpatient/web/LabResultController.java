package cn.hip.outpatient.web;

import cn.hip.outpatient.entity.OutpCriticalAlert;
import cn.hip.outpatient.repository.OutpCriticalAlertRepository;
import cn.hip.outpatient.repository.OutpLabResultRepository;
import cn.hip.outpatient.repository.OutpRegistrationRepository;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import cn.hip.platform.empi.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 检验结果查询与危急值闭环（v33：通知开单医师+接收确认+时限+处置留痕） */
@RestController
@RequestMapping("/api/outpatient")
@PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN')")   // 1.0.9：权限清点补齐
@RequiredArgsConstructor
public class LabResultController {

    private final OutpLabResultRepository resultRepository;
    private final OutpCriticalAlertRepository alertRepository;
    private final OutpRegistrationRepository registrationRepository;
    private final PatientRepository patientRepository;
    private final CurrentUserService currentUserService;
    private final JdbcTemplate jdbc;

    @GetMapping("/lab-results")
    public R<Object> results(@RequestParam Long orderId) {
        return R.ok(resultRepository.findByOrderIdOrderByIdAsc(orderId));
    }

    @GetMapping("/critical-alerts")
    public R<List<Map<String, Object>>> alerts(@RequestParam(defaultValue = "NEW") String status) {
        var list = "ALL".equals(status)
                ? alertRepository.findTop50ByOrderByIdDesc()
                : alertRepository.findByStatusOrderByIdDesc(status);
        return R.ok(list.stream().map(a -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", a.getId());
            m.put("content", a.getContent());
            m.put("status", a.getStatus());
            m.put("createdAt", a.getCreatedAt());
            registrationRepository.findById(a.getRegistrationId()).ifPresent(r ->
                    patientRepository.findById(r.getPatientId()).ifPresent(p -> {
                        m.put("patientNo", p.getPatientNo());
                        m.put("patientName", p.getName());
                    }));
            return (Map<String, Object>) m;
        }).toList());
    }

    @PutMapping("/critical-alerts/{id}/handle")
    public R<Void> handle(@PathVariable Long id, Authentication auth) {
        OutpCriticalAlert a = alertRepository.findById(id).orElse(null);
        if (a == null) return R.fail(7101, "告警不存在");
        a.setStatus("HANDLED");
        a.setHandlerId(currentUserService.idOf(auth));
        a.setHandledAt(Instant.now());
        alertRepository.save(a);
        return R.ok();
    }

    // ===== v33：危急值闭环——开单医师接收确认 =====

    public record AckReq(String disposition) {}

    /** 我的待确认危急值：应通知我（开单医师）且未确认的，含是否超期 */
    @GetMapping("/critical-alerts/my-pending")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP')")
    public R<List<Map<String, Object>>> myPending(Authentication auth) {
        Long me = currentUserService.idOf(auth);
        return R.ok(jdbc.queryForList("""
                select a.id, a.source, a.content, a.created_at, a.deadline_at,
                       (a.deadline_at < now()) as overdue, p.patient_no, p.name as patient_name
                from outp_critical_alert a
                join outp_registration r on r.id = a.registration_id
                join empi_patient p on p.id = r.patient_id
                where a.status = 'NEW' and a.notify_to_user_id = ?
                order by a.deadline_at
                """, me));
    }

    /**
     * 开单医师接收确认 + 处置留痕（闭环终态）。
     * 只有被通知的开单医师本人或管理员能确认——防他人替确认掩盖漏报；条件更新防并发重复确认。
     */
    @PutMapping("/critical-alerts/{id}/acknowledge")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP')")
    @Transactional
    public R<Void> acknowledge(@PathVariable Long id, @RequestBody AckReq req, Authentication auth) {
        if (req.disposition() == null || req.disposition().isBlank()) return R.fail(7102, "处置措施必填");
        var rows = jdbc.queryForList(
                "select notify_to_user_id, status from outp_critical_alert where id = ?", id);
        if (rows.isEmpty()) return R.fail(7101, "告警不存在");
        Long me = currentUserService.idOf(auth);
        Object notifyToObj = rows.get(0).get("notify_to_user_id");
        Long notifyTo = notifyToObj == null ? null : ((Number) notifyToObj).longValue();
        boolean admin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!admin && notifyTo != null && !notifyTo.equals(me)) {
            return R.fail(7103, "仅开单医师本人可确认该危急值");
        }
        int n = jdbc.update("""
                update outp_critical_alert
                set status = 'HANDLED', ack_by = ?, ack_at = now(), disposition = ?,
                    handler_id = ?, handled_at = now()
                where id = ? and status = 'NEW'
                """, me, req.disposition(), me, id);
        return n == 0 ? R.fail(7102, "该危急值已确认或已处理") : R.ok();
    }

    /** 超期看板：已过应确认时限仍未确认的危急值（护士长/管理值守追踪，防漏报） */
    @GetMapping("/critical-alerts/overdue")
    public R<List<Map<String, Object>>> overdue() {
        return R.ok(jdbc.queryForList("""
                select a.id, a.source, a.content, a.created_at, a.deadline_at,
                       u.real_name as notify_to_name, p.patient_no, p.name as patient_name
                from outp_critical_alert a
                join outp_registration r on r.id = a.registration_id
                join empi_patient p on p.id = r.patient_id
                left join sys_user u on u.id = a.notify_to_user_id
                where a.status = 'NEW' and a.deadline_at is not null and a.deadline_at < now()
                order by a.deadline_at
                """));
    }
}
