package cn.hip.outpatient.web;

import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.repository.SysDeptRepository;
import cn.hip.platform.core.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/outpatient/schedules")
@RequiredArgsConstructor
public class OutpScheduleController {

    private final cn.hip.outpatient.service.ScheduleService service;
    private final cn.hip.outpatient.service.ScheduleAccess access;
    private final OutpScheduleRepository scheduleRepository;
    private final SysDeptRepository deptRepository;
    private final SysUserRepository userRepository;

    @GetMapping
    public R<List<Map<String, Object>>> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long deptId,
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        if (includeDisabled) access.admin();
        var schedules = includeDisabled
                ? (deptId == null ? scheduleRepository.findByScheduleDateOrderByDeptIdAscIdAsc(date)
                    : scheduleRepository.findByScheduleDateAndDeptIdOrderByIdAsc(date, deptId))
                : deptId == null
                ? scheduleRepository.findByScheduleDateAndEnabledTrueOrderByDeptIdAscIdAsc(date)
                : scheduleRepository.findByScheduleDateAndDeptIdAndEnabledTrueOrderByIdAsc(date, deptId);
        return R.ok(schedules.stream().map(this::toDto).toList());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public R<Map<String, Object>> create(@RequestBody cn.hip.outpatient.service.ScheduleService.CreateRequest req) {
        try {
            return R.ok(toDto(service.create(req)));
        } catch (cn.hip.outpatient.service.RegistrationService.BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @PutMapping("/{id}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        try {
            service.setEnabled(id, enabled);
            return R.ok();
        } catch (cn.hip.outpatient.service.RegistrationService.BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    private Map<String, Object> toDto(OutpSchedule s) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", s.getId());
        m.put("deptId", s.getDeptId());
        m.put("deptName", deptRepository.findById(s.getDeptId()).map(d -> d.getName()).orElse(""));
        m.put("doctorId", s.getDoctorId());
        m.put("doctorName", s.getDoctorId() == null ? ""
                : userRepository.findById(s.getDoctorId()).map(u -> u.getRealName()).orElse(""));
        m.put("scheduleDate", s.getScheduleDate());
        m.put("shift", s.getShift());
        m.put("regType", s.getRegType());
        m.put("fee", s.getFee());
        m.put("capacity", s.getCapacity());
        m.put("booked", s.getBooked());
        m.put("remaining", s.getCapacity() - s.getBooked());
        m.put("enabled", s.getEnabled());
        return m;
    }
}
