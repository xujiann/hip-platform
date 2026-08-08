package cn.hip.outpatient.web;

import cn.hip.outpatient.entity.OutpRegistration;
import cn.hip.outpatient.repository.OutpRegistrationRepository;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.repository.SysDeptRepository;
import cn.hip.platform.empi.repository.PatientRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 候诊队列与叫号：REGISTERED 候诊 → CALLED 已叫号 → VISITED 接诊中 */
@RestController
@RequestMapping("/api/outpatient/queue")
@RequiredArgsConstructor
public class QueueController {

    private final OutpRegistrationRepository registrationRepository;
    private final PatientRepository patientRepository;
    private final SysDeptRepository deptRepository;
    private final EntityManager entityManager;

    /** 大屏队列：某科室今日候诊+已叫号+过号 */
    @GetMapping
    public R<Map<String, Object>> queue(@RequestParam Long deptId) {
        var today = registrationRepository.findByVisitDateOrderByIdDesc(LocalDate.now()).stream()
                .filter(r -> r.getDeptId().equals(deptId))
                .toList();
        var m = new LinkedHashMap<String, Object>();
        m.put("deptName", deptRepository.findById(deptId).map(d -> d.getName()).orElse(""));
        m.put("waiting", today.stream().filter(r -> "REGISTERED".equals(r.getStatus()))
                .sorted((a, b) -> a.getRegNo() - b.getRegNo()).map(this::brief).toList());
        m.put("called", today.stream().filter(r -> "CALLED".equals(r.getStatus())).map(this::brief).toList());
        m.put("visiting", today.stream().filter(r -> "VISITED".equals(r.getStatus())).map(this::brief).toList());
        m.put("passed", today.stream().filter(r -> "PASSED".equals(r.getStatus()))
                .sorted((a, b) -> a.getRegNo() - b.getRegNo()).map(this::brief).toList());
        return R.ok(m);
    }

    /** 过号：叫号未到 → 移入过号队列（大屏显示，可重呼） */
    @PostMapping("/pass")
    @Transactional
    public R<Void> pass(@RequestParam Long registrationId) {
        var reg = registrationRepository.findById(registrationId).orElse(null);
        if (reg == null || !"CALLED".equals(reg.getStatus())) {
            return R.fail(3302, "仅已叫号未到的患者可过号");
        }
        reg.setStatus("PASSED");
        registrationRepository.save(reg);
        return R.ok();
    }

    /** 重呼过号患者：过号队列 → 重新叫号（顺延效果：不占候诊队首） */
    @PostMapping("/recall")
    @Transactional
    public R<Map<String, Object>> recall(@RequestParam Long registrationId) {
        var reg = registrationRepository.findById(registrationId).orElse(null);
        if (reg == null || !"PASSED".equals(reg.getStatus())) {
            return R.fail(3303, "仅过号患者可重呼");
        }
        reg.setStatus("CALLED");
        registrationRepository.save(reg);
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
    public R<Map<String, Object>> callNext(@RequestParam Long deptId) {
        var next = registrationRepository.findByVisitDateOrderByIdDesc(LocalDate.now()).stream()
                .filter(r -> r.getDeptId().equals(deptId) && "REGISTERED".equals(r.getStatus()))
                .min((a, b) -> a.getRegNo() - b.getRegNo());
        if (next.isEmpty()) {
            return R.fail(3301, "当前无候诊患者");
        }
        OutpRegistration reg = next.get();
        // 条件更新：两台叫号器并发会取到同一最小号，双写 CALLED 与两条 call_log
        if (registrationRepository.claimCall(reg.getId()) == 0) {
            return R.fail(3302, "该患者已被叫号，请重试");
        }
        reg = registrationRepository.findById(reg.getId()).orElseThrow();   // 取回更新后的状态
        entityManager.createNativeQuery(
                        "insert into outp_call_log(registration_id, dept_id, reg_no, called_at) values (?,?,?,?)")
                .setParameter(1, reg.getId()).setParameter(2, deptId)
                .setParameter(3, reg.getRegNo()).setParameter(4, Instant.now())
                .executeUpdate();
        return R.ok(brief(reg));
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
