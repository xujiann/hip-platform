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
    private final cn.hip.outpatient.service.EmergencyAccess access;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @GetMapping
    public R<List<OutpTriage>> list() {
        var me=access.actor();
        return R.ok(triageRepository.visible(me.id(),me.coordinates()));
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
        var me=access.actor();
        triage.setDoctorId(me.coordinates()?null:me.id());
        triage.setCreatedAt(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        triage.setStatus("TRIAGED");
        triage.setId(null);
        triage.setNurseId(me.id());
        return R.ok(triageRepository.save(triage));
    }

    @PutMapping("/{id}/status")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> setStatus(@PathVariable Long id, @RequestParam String status) {
        if (!List.of("TRIAGED", "IN_TREATMENT", "ADMITTED", "DISCHARGED").contains(status))
            return R.fail(9705, "非法状态");
        var me=access.actor();
        var t=access.lockTriage(id,9704,"ADMITTED".equals(status)||"DISCHARGED".equals(status));
        Long doctor=access.number(t.get("doctor_id"));
        if (doctor==null && "IN_TREATMENT".equals(status) && me.doctor() && !me.coordinates()) doctor=me.id();
        jdbc.update("update outp_triage set status=?,doctor_id=? where id=?",status,doctor,id);
        return R.ok();
    }

    public record DoctorReq(Long doctorId) {}
    @PutMapping("/{id}/doctor")
    @PreAuthorize("hasAnyRole('ADMIN','NURSE')")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> assignDoctor(@PathVariable Long id,@RequestBody DoctorReq req) {
        var me=access.actor();
        if(!me.coordinates())throw access.denied();
        access.lockTriage(id,9704,false);
        access.validateDoctor(req.doctorId());
        jdbc.update("update outp_triage set doctor_id=? where id=?",req.doctorId(),id);
        return R.ok();
    }
    @GetMapping("/doctors")
    @PreAuthorize("hasAnyRole('ADMIN','NURSE')")
    public R<java.util.List<java.util.Map<String,Object>>> doctors() {
        var me=access.actor();if(!me.coordinates())throw access.denied();
        return R.ok(jdbc.queryForList("select u.id,u.real_name as name from sys_user u where u.enabled=true and exists(select 1 from sys_user_role ur join sys_role r on r.id=ur.role_id where ur.user_id=u.id and r.code='DOCTOR_OUTP') order by u.real_name,u.id"));
    }
}
