package cn.hip.outpatient.service;

import cn.hip.outpatient.entity.OutpCriticalAlert;
import cn.hip.outpatient.entity.OutpLabResult;
import cn.hip.outpatient.entity.OutpOrder;
import cn.hip.outpatient.repository.OutpCriticalAlertRepository;
import cn.hip.outpatient.repository.OutpLabResultRepository;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.platform.integration.event.LabResultReceivedEvent;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.Objects;

/** LIS 回传结果落库：按申请单号定位医嘱 → 结果入库（缺 flag 按参考区间自动判）→ 医嘱自动执行 → 危急值闭环告警 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LabResultListener {

    /** 危急值标志：无论上游给还是参考区间判出，HH/LL 即危急 */
    private static final Set<String> CRITICAL_FLAGS = Set.of("HH", "LL");

    private final OutpOrderRepository orderRepository;
    private final OutpLabResultRepository resultRepository;
    private final OutpCriticalAlertRepository alertRepository;
    private final ReferenceRangeService referenceRangeService;
    private final ConfigReader configReader;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    @EventListener
    @Transactional
    public void onLabResult(LabResultReceivedEvent event) {
        entityManager.flush();
        // Refund takes C -> ascending O. Lock every member of the group in the same
        // O order before inserting any result/FK row; a later FK KEY SHARE is too weak
        // and its upgrade can deadlock with a waiting refund. Manual publish uses O -> S.
        var ids = jdbc.queryForList("select id from outp_order where group_no=? order by id for update",
                Long.class, event.orderGroupNo());
        var orders = new ArrayList<OutpOrder>();
        for (Long id : ids) {
            var order = orderRepository.findById(id).orElseThrow();
            entityManager.refresh(order);
            if (Objects.equals(event.orderGroupNo(), order.getGroupNo()) && "LAB".equals(order.getOrderType())
                    && ("CHARGED".equals(order.getStatus()) || "EXECUTED".equals(order.getStatus())))
                orders.add(order);
        }
        if (orders.isEmpty()) {
            throw new IllegalStateException("未找到可回传的检验申请: " + event.orderGroupNo());
        }
        int deadlineMinutes = configReader.getInt("critical_ack_deadline_minutes", 10);
        for (OutpOrder order : orders) {
            // 患者性别/年龄一次取（参考区间按性别年龄匹配）；找不到不阻断落库
            String sex = null;
            Integer ageDays = null;
            try {
                var pt = jdbc.queryForMap(
                        "select p.sex, p.birth_date from outp_registration r "
                                + "join empi_patient p on p.id = r.patient_id where r.id = ?", order.getRegistrationId());
                sex = (String) pt.get("sex");
                if (pt.get("birth_date") instanceof java.sql.Date d) {
                    ageDays = (int) ChronoUnit.DAYS.between(d.toLocalDate(), LocalDate.now());
                }
            } catch (Exception ignore) {
                // 患者信息缺失不影响结果落库，仅退化为不按区间判
            }

            StringBuilder critical = new StringBuilder();
            for (var item : event.items()) {
                // 上游给了 flag 就尊重；缺失时按参考区间自动判（数值型且有匹配区间才判得出）
                String flag = item.abnormalFlag();
                if (flag == null || flag.isBlank()) {
                    flag = referenceRangeService.evaluate(item.code(), sex, ageDays, item.value());
                }
                OutpLabResult r = new OutpLabResult();
                r.setOrderId(order.getId());
                r.setItemCode(item.code());
                r.setItemName(item.name());
                r.setResultValue(item.value());
                r.setUnit(item.unit());
                r.setRefRange(item.refRange());
                r.setAbnormalFlag(flag);
                resultRepository.save(r);
                if (flag != null && CRITICAL_FLAGS.contains(flag)) {
                    critical.append("%s=%s%s(%s) ".formatted(
                            item.name(), item.value(), item.unit() == null ? "" : item.unit(), flag));
                }
            }
            if ("CHARGED".equals(order.getStatus())) {
                if (jdbc.update("update outp_order set status='EXECUTED' where id=? and order_type='LAB' and status='CHARGED'",
                        order.getId()) != 1)
                    throw new IllegalStateException("检验申请状态已变化: " + event.orderGroupNo());
                entityManager.refresh(order);
            }
            if (!critical.isEmpty()) {
                Long doctorId = order.getDoctorId();
                if (doctorId == null) {
                    // 医嘱未记开单医师时兜底取接诊医师，闭环仍能"通知到人"
                    var reg = jdbc.queryForList(
                            "select doctor_id from outp_registration where id = ?", Long.class, order.getRegistrationId());
                    doctorId = reg.isEmpty() ? null : reg.get(0);
                }
                OutpCriticalAlert alert = new OutpCriticalAlert();
                alert.setOrderId(order.getId());
                alert.setRegistrationId(order.getRegistrationId());
                alert.setSource("LAB");
                alert.setContent("【危急值】%s：%s".formatted(order.getItemName(), critical.toString().trim()));
                alert.setNotifyToUserId(doctorId);
                alert.setNotifiedAt(Instant.now());
                alert.setDeadlineAt(Instant.now().plus(deadlineMinutes, ChronoUnit.MINUTES));
                alertRepository.save(alert);
                log.warn("危急值告警: order={} notifyTo={} {}", order.getId(), doctorId, critical);
            }
        }
    }
}
