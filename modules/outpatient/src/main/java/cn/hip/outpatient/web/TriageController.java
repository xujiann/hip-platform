package cn.hip.outpatient.web;

import cn.hip.outpatient.entity.OutpTriage;
import cn.hip.outpatient.repository.OutpTriageRepository;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 急诊预检分诊 */
@RestController
@RequestMapping("/api/outpatient/triage")
@PreAuthorize("hasAnyRole('ADMIN','NURSE','DOCTOR_OUTP')")   // 1.0.9：权限清点补齐
@RequiredArgsConstructor
public class TriageController {

    private final OutpTriageRepository triageRepository;
    private final CurrentUserService currentUserService;

    @GetMapping
    public R<List<OutpTriage>> list() {
        return R.ok(triageRepository.findTop100ByOrderByLevelAscIdDesc());
    }

    @PostMapping
    public R<OutpTriage> create(@RequestBody OutpTriage triage, Authentication auth) {
        if (triage.getPatientName() == null || triage.getPatientName().isBlank()) {
            return R.fail(9701, "患者姓名（或无名氏编号）必填");
        }
        if (triage.getLevel() == null || triage.getLevel() < 1 || triage.getLevel() > 4) {
            return R.fail(9702, "分级须为 1-4");
        }
        if (triage.getChiefComplaint() == null || triage.getChiefComplaint().isBlank()) {
            return R.fail(9703, "主诉必填");
        }
        triage.setId(null);
        triage.setNurseId(currentUserService.idOf(auth));
        return R.ok(triageRepository.save(triage));
    }

    @PutMapping("/{id}/status")
    public R<Void> setStatus(@PathVariable Long id, @RequestParam String status) {
        var t = triageRepository.findById(id).orElse(null);
        if (t == null) return R.fail(9704, "分诊记录不存在");
        if (!List.of("TRIAGED", "IN_TREATMENT", "ADMITTED", "DISCHARGED").contains(status)) {
            return R.fail(9705, "非法状态");
        }
        t.setStatus(status);
        triageRepository.save(t);
        return R.ok();
    }
}
