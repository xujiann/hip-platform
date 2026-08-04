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

    /** 大屏队列：某科室今日候诊+已叫号 */
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
        return R.ok(m);
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
        reg.setStatus("CALLED");
        registrationRepository.save(reg);
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
