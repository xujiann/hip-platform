package cn.hip.outpatient.web;

import cn.hip.outpatient.entity.OutpRegistration;
import cn.hip.outpatient.repository.OutpRegistrationRepository;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.repository.SysDeptRepository;
import cn.hip.platform.empi.repository.PatientRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Comparator;
import org.springframework.jdbc.core.JdbcTemplate;
import cn.hip.outpatient.service.QueueAccess;
import org.springframework.security.access.AccessDeniedException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import cn.hip.platform.core.config.BusinessDates;

/** 候诊队列与叫号：REGISTERED 候诊 → CALLED 已叫号 → VISITED 接诊中 */
@RestController
@RequestMapping("/api/outpatient/queue")
@PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE','TECHNICIAN','CASHIER')")   // 1.0.9：权限清点补齐
@RequiredArgsConstructor
public class QueueController {

    private final OutpRegistrationRepository registrationRepository;
    private final PatientRepository patientRepository;
    private final SysDeptRepository deptRepository;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final QueueAccess access;
    private static final Comparator<OutpRegistration> NUMBER_ORDER = Comparator.comparingInt(OutpRegistration::getRegNo).thenComparing(OutpRegistration::getId);

    /** 大屏队列：某科室今日候诊+已叫号+过号 */
    @GetMapping
    public R<Map<String, Object>> queue(@RequestParam Long deptId) {
        access.read();
        var today = registrationRepository.findByVisitDateOrderByIdDesc(BusinessDates.today()).stream()
                .filter(r -> r.getDeptId().equals(deptId))
                .toList();
        var m = new LinkedHashMap<String, Object>();
        m.put("deptName", deptRepository.findById(deptId).map(d -> d.getName()).orElse(""));
        m.put("waiting", today.stream().filter(r -> "REGISTERED".equals(r.getStatus()))
                .sorted(NUMBER_ORDER).map(this::brief).toList());
        m.put("called", today.stream().filter(r -> "CALLED".equals(r.getStatus())).map(this::brief).toList());
        m.put("visiting", today.stream().filter(r -> "VISITED".equals(r.getStatus())).map(this::brief).toList());
        m.put("passed", today.stream().filter(r -> "PASSED".equals(r.getStatus()))
                .sorted(NUMBER_ORDER).map(this::brief).toList());
        return R.ok(m);
    }

    /** 过号：叫号未到 → 移入过号队列（大屏显示，可重呼） */
    @PostMapping("/pass")
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP')")
    public R<Void> pass(@RequestParam Long registrationId) {
        access.write();
        var reg = lockCurrent(registrationId);
        if (reg == null || !"CALLED".equals(reg.getStatus())) {
            return R.fail(3302, "仅已叫号未到的患者可过号");
        }
        transition(reg, "CALLED", "PASSED");
        return R.ok();
    }

    /** 重呼过号患者：过号队列 → 重新叫号（顺延效果：不占候诊队首） */
    @PostMapping("/recall")
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP')")
    public R<Map<String, Object>> recall(@RequestParam Long registrationId) {
        access.write();
        var reg = lockCurrent(registrationId);
        if (reg == null || !"PASSED".equals(reg.getStatus())) {
            return R.fail(3303, "仅过号患者可重呼");
        }
        transition(reg, "PASSED", "CALLED");
        entityManager.createNativeQuery(
                        "insert into outp_call_log(registration_id, dept_id, reg_no, called_at) values (?,?,?,?)")
                .setParameter(1, reg.getId()).setParameter(2, reg.getDeptId())
                .setParameter(3, reg.getRegNo()).setParameter(4, Instant.now())
                .executeUpdate();
        return R.ok(brief(reg));
    }

    /** 叫下一号：取候诊最小号序置为 CALLED 并留痕 */
    @PostMapping("/call-next")
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP')")
    public R<Map<String, Object>> callNext(@RequestParam Long deptId) {
        access.write();
        var next = registrationRepository.findByVisitDateOrderByIdDesc(BusinessDates.today()).stream()
                .filter(r -> r.getDeptId().equals(deptId) && "REGISTERED".equals(r.getStatus()))
                .min(NUMBER_ORDER);
        if (next.isEmpty()) {
            return R.fail(3301, "当前无候诊患者");
        }
        OutpRegistration reg = lockCurrent(next.get().getId());
        if (reg == null || !"REGISTERED".equals(reg.getStatus())) {
            return R.fail(3302, "该患者已被叫号，请重试");
        }
        transition(reg, "REGISTERED", "CALLED");
        entityManager.createNativeQuery(
                        "insert into outp_call_log(registration_id, dept_id, reg_no, called_at) values (?,?,?,?)")
                .setParameter(1, reg.getId()).setParameter(2, reg.getDeptId())
                .setParameter(3, reg.getRegNo()).setParameter(4, Instant.now())
                .executeUpdate();
        return R.ok(brief(reg));
    }

    /** Same row lock as startVisit/cancel; refresh even if a caller preloaded an old JPA entity. */
    private OutpRegistration lockCurrent(Long id) {
        entityManager.flush();
        var rows = jdbc.queryForList("select id from outp_registration where id=? for update", Long.class, id);
        access.write(); // The actor may have been disabled while waiting for this row.
        if (rows.isEmpty()) return null;
        var reg = registrationRepository.findById(id).orElseThrow();
        entityManager.refresh(reg);
        if (!BusinessDates.today().equals(reg.getVisitDate()))
            throw new AccessDeniedException("仅可处理当日叫号队列");
        return reg;
    }

    private void transition(OutpRegistration reg, String from, String to) {
        // Queue actions never rewrite doctor, identity, fees or numbers.
        int changed = jdbc.update("update outp_registration set status=? where id=? and status=? and visit_date=?",
                to, reg.getId(), from, BusinessDates.today());
        if (changed != 1) throw new cn.hip.outpatient.service.RegistrationService.BizException(
                "PASSED".equals(from) ? 3303 : 3302, "队列状态已变化，请刷新后重试");
        entityManager.refresh(reg);
    }

    private Map<String, Object> brief(OutpRegistration r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("registrationId", r.getId());
        m.put("regNo", r.getRegNo());
        m.put("status", r.getStatus());
        patientRepository.findById(r.getPatientId()).ifPresent(p -> m.put("patientName", p.getName()));
        return m;
    }
}
