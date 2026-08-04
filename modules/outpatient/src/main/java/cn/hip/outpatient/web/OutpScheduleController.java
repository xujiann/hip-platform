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

    private final OutpScheduleRepository scheduleRepository;
    private final SysDeptRepository deptRepository;
    private final SysUserRepository userRepository;

    @GetMapping
    public R<List<Map<String, Object>>> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long deptId) {
        var schedules = deptId == null
                ? scheduleRepository.findByScheduleDateAndEnabledTrueOrderByDeptIdAscIdAsc(date)
                : scheduleRepository.findByScheduleDateAndDeptIdAndEnabledTrueOrderByIdAsc(date, deptId);
        return R.ok(schedules.stream().map(this::toDto).toList());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public R<Map<String, Object>> create(@RequestBody OutpSchedule schedule) {
        if (schedule.getDeptId() == null || schedule.getScheduleDate() == null
                || schedule.getCapacity() == null || schedule.getCapacity() <= 0) {
            return R.fail(3101, "科室、日期、号源数量为必填项");
        }
        schedule.setId(null);
        schedule.setBooked(0);
        return R.ok(toDto(scheduleRepository.save(schedule)));
    }

    @PutMapping("/{id}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        var s = scheduleRepository.findById(id).orElse(null);
        if (s == null) return R.fail(3102, "排班不存在");
        s.setEnabled(enabled);
        scheduleRepository.save(s);
        return R.ok();
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
