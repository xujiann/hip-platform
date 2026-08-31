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

    /** 挂号：占号成功后以占号后的 booked 值作为叫号序号 */
    @Transactional
    public OutpRegistration register(Long patientId, Long scheduleId) {
        OutpSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BizException(3001, "号源不存在"));
        // 查重仅为友好提示；真正的防线是 uq_outp_reg_active 唯一索引（并发下 find-then-insert 会双挂）
        registrationRepository.findByScheduleIdAndPatientIdAndStatus(scheduleId, patientId, "REGISTERED")
                .ifPresent(r -> { throw new BizException(3002, "该患者已挂此号，勿重复挂号"); });
        if (scheduleRepository.occupySlot(scheduleId) == 0) {
            throw new BizException(3003, "号源已满");
        }
        OutpSchedule after = scheduleRepository.findById(scheduleId).orElseThrow();

        OutpRegistration reg = new OutpRegistration();
        reg.setRegNo(after.getBooked());
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

    /** 退号：仅未就诊可退，释放号源 */
    @Transactional
    public OutpRegistration cancel(Long registrationId) {
        OutpRegistration reg = registrationRepository.findById(registrationId)
                .orElseThrow(() -> new BizException(3004, "挂号记录不存在"));
        if (!"REGISTERED".equals(reg.getStatus())) {
            throw new BizException(3005, "当前状态不可退号");
        }
        // 挂号费已收费须先退费；未收费自动作废
        for (OutpOrder o : orderRepository.findByRegistrationIdOrderByIdAsc(registrationId)) {
            if ("CHARGED".equals(o.getStatus())) {
                throw new BizException(3006, "存在已收费项目，请先退费再退号");
            }
            if ("CREATED".equals(o.getStatus())) {
                o.setStatus("CANCELLED");
                orderRepository.save(o);
            }
        }
        reg.setStatus("CANCELLED");
        reg.setCancelledAt(java.time.Instant.now());
        scheduleRepository.releaseSlot(reg.getScheduleId());
        return registrationRepository.save(reg);
    }
}
