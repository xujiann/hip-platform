package cn.hip.outpatient.service;

import cn.hip.outpatient.entity.OutpOrder;
import cn.hip.outpatient.entity.OutpRegistration;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.repository.OutpRegistrationRepository;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final jakarta.persistence.EntityManager em;
    private final RegistrationAccess access;
    private final ScheduleNumberService numbers;
    private final RegistrationEligibilityService eligibility;
    private final OutpScheduleRepository scheduleRepository;
    private final OutpRegistrationRepository registrationRepository;
    private final OutpOrderRepository orderRepository;

    /** 域内保留类型（现有 catch/测试不动），实质语义在基类（1.1.9） */
    public static class BizException extends cn.hip.platform.core.common.HipBizException {
        public BizException(int code, String message) {
            super(code, message);
        }
    }

    /**
     * v37 预约签到转正式挂号：预约时已占 schedule 号（appt_no 与 walk-in regNo 同池），
     * 签到只建挂号记录不再占号——否则一个患者占两个号。挂号费订单行与 register() 同口径。
     */
    @Transactional
    public OutpRegistration registerFromAppointment(Long patientId, Long scheduleId, int regNo) {
        OutpSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BizException(3001, "号源不存在"));
        eligibility.lock(scheduleId, patientId);
        if (eligibility.hasPending(scheduleId, patientId)) throw duplicate();
        OutpRegistration reg = new OutpRegistration();
        reg.setRegNo(regNo);
        reg.setPatientId(patientId);
        reg.setScheduleId(scheduleId);
        reg.setDeptId(schedule.getDeptId());
        reg.setDoctorId(schedule.getDoctorId());
        reg.setVisitDate(schedule.getScheduleDate());
        reg.setFee(schedule.getFee());
        try {
            reg = registrationRepository.saveAndFlush(reg);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new BizException(3002, "该患者已挂此号，勿重复挂号");
        }
        if (reg.getFee() != null && reg.getFee().compareTo(BigDecimal.ZERO) > 0) {
            OutpOrder regFee = new OutpOrder();
            regFee.setRegistrationId(reg.getId());
            regFee.setGroupNo("GH-" + reg.getId());
            regFee.setOrderType("REG");
            regFee.setItemId(0L);
            regFee.setItemCode("REG");
            regFee.setItemName("挂号费");
            regFee.setUnit("次");
            regFee.setQty(1);
            regFee.setUnitPrice(reg.getFee());
            regFee.setAmount(reg.getFee());
            orderRepository.save(regFee);
        }
        return reg;
    }

    /** 挂号：占用容量与发出排班单调号码在同一事务完成。 */
    @Transactional
    public OutpRegistration register(Long patientId, Long scheduleId) {
        return registerInternal(patientId, scheduleId, false);
    }

    @Transactional
    public OutpRegistration registerFromDesk(Long patientId, Long scheduleId) {
        access.staff();
        return registerInternal(patientId, scheduleId, true);
    }

    private OutpRegistration registerInternal(Long patientId, Long scheduleId, boolean desk) {
        OutpSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BizException(3001, "号源不存在"));
        em.flush();
        var patients = jdbc.queryForList("select active from empi_patient where id=? for share", Boolean.class, patientId);
        if (patients.isEmpty() || !Boolean.TRUE.equals(patients.get(0))) {
            throw new BizException(3007, "挂号患者不存在或已停用");
        }
        if (desk) access.staff();
        eligibility.lock(scheduleId, patientId);
        if (desk) access.staff();
        if (eligibility.hasPending(scheduleId, patientId)) throw duplicate();
        int regNo = numbers.occupy(scheduleId, 3003);
        if (desk) access.staff();

        OutpRegistration reg = new OutpRegistration();
        reg.setRegNo(regNo);
        reg.setPatientId(patientId);
        reg.setScheduleId(scheduleId);
        reg.setDeptId(schedule.getDeptId());
        reg.setDoctorId(schedule.getDoctorId());
        reg.setVisitDate(schedule.getScheduleDate());
        reg.setFee(schedule.getFee());
        try {
            reg = registrationRepository.saveAndFlush(reg);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new BizException(3002, "该患者已挂此号，勿重复挂号");
        }

        // 挂号费作为一条订单行进入统一收费队列（费用不落两处账）
        if (reg.getFee() != null && reg.getFee().compareTo(BigDecimal.ZERO) > 0) {
            OutpOrder regFee = new OutpOrder();
            regFee.setRegistrationId(reg.getId());
            regFee.setGroupNo("GH-" + reg.getId());
            regFee.setOrderType("REG");
            regFee.setItemId(0L);
            regFee.setItemCode("REG");
            regFee.setItemName("挂号费");
            regFee.setUnit("次");
            regFee.setQty(1);
            regFee.setUnitPrice(reg.getFee());
            regFee.setAmount(reg.getFee());
            orderRepository.save(regFee);
        }
        return reg;
    }

    private BizException duplicate() {
        return new BizException(3002, "该患者此排班已有预约或待接诊挂号，请勿重复挂号");
    }

    /** Trusted internal caller; desk authorization is applied by the separate entry point. */
    @Transactional
    public OutpRegistration cancel(Long registrationId) {
        return cancelInternal(registrationId, false);
    }

    @Transactional
    public OutpRegistration cancelFromDesk(Long registrationId) {
        access.staff();
        return cancelInternal(registrationId, true);
    }

    private OutpRegistration cancelInternal(Long registrationId, boolean desk) {
        em.flush();
        if (jdbc.queryForList("select id from outp_registration where id=? for update", Long.class, registrationId).isEmpty()) {
            throw new BizException(3004, "挂号记录不存在");
        }
        var reg = registrationRepository.findById(registrationId).orElseThrow();
        // A caller may have loaded this entity before waiting for the database lock.
        em.refresh(reg);
        if (desk) access.staff();
        if (!"REGISTERED".equals(reg.getStatus())) throw new BizException(3005, "当前状态不可退号");

        // Registration -> appointment -> orders -> slot -> schedule. Both appointment
        // booking and cancellation also take the slot before the schedule.
        var links = jdbc.queryForList("select patient_id,schedule_id,slot_id,status from outp_appointment where registration_id=? order by id for update", registrationId);
        Long slotId = null;
        if (!links.isEmpty()) {
            var link = links.get(0);
            if (links.size() != 1 || !"CHECKED_IN".equals(link.get("status"))
                    || !reg.getPatientId().equals(((Number) link.get("patient_id")).longValue())
                    || !reg.getScheduleId().equals(((Number) link.get("schedule_id")).longValue())) throw inconsistent();
            slotId = ((Number) link.get("slot_id")).longValue();
        }
        var orders = jdbc.queryForList("select status from outp_order where registration_id=? order by id for update", String.class, registrationId);
        if (desk) access.staff();
        if (orders.stream().anyMatch(status -> !"CREATED".equals(status) && !"CANCELLED".equals(status))) {
            throw new BizException(3006, "存在已收费或已执行项目，请先按流程退费再退号");
        }
        if (slotId != null) {
            var slots = jdbc.queryForList("select schedule_id,booked from outp_schedule_slot where id=? for update", slotId);
            if (slots.size() != 1 || !reg.getScheduleId().equals(((Number) slots.get(0).get("schedule_id")).longValue())
                    || ((Number) slots.get(0).get("booked")).intValue() <= 0) throw inconsistent();
        }
        var schedules = jdbc.queryForList("select booked from outp_schedule where id=? for update", Integer.class, reg.getScheduleId());
        if (schedules.size() != 1 || schedules.get(0) <= 0) throw inconsistent();
        if (desk) access.staff();

        // Validate every object before the first mutation. The transaction still rolls
        // back all claims if a conditional update ever fails.
        if (jdbc.update("update outp_registration set status='CANCELLED',cancelled_at=current_timestamp where id=? and status='REGISTERED'", registrationId) != 1) {
            throw new BizException(3005, "当前状态不可退号");
        }
        jdbc.update("update outp_order set status='CANCELLED' where registration_id=? and status='CREATED'", registrationId);
        if (slotId != null && jdbc.update("update outp_schedule_slot set booked=booked-1 where id=? and booked>0", slotId) != 1) throw inconsistent();
        if (jdbc.update("update outp_schedule set booked=booked-1 where id=? and booked>0", reg.getScheduleId()) != 1) throw inconsistent();
        // Preserve CHECKED_IN and registration_id as the appointment audit trail.
        em.clear();
        return registrationRepository.findById(registrationId).orElseThrow();
    }

    private BizException inconsistent() {
        return new BizException(3008, "预约关联或号源计数不一致，请核对后处理");
    }
}
