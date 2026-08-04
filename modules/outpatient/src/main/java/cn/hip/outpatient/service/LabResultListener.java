package cn.hip.outpatient.service;

import cn.hip.outpatient.entity.OutpCriticalAlert;
import cn.hip.outpatient.entity.OutpLabResult;
import cn.hip.outpatient.entity.OutpOrder;
import cn.hip.outpatient.repository.OutpCriticalAlertRepository;
import cn.hip.outpatient.repository.OutpLabResultRepository;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.platform.integration.event.LabResultReceivedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/** LIS 回传结果落库：按申请单号定位医嘱 → 结果入库 → 医嘱自动执行 → 危急值告警 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LabResultListener {

    private static final Set<String> CRITICAL_FLAGS = Set.of("HH", "LL");

    private final OutpOrderRepository orderRepository;
    private final OutpLabResultRepository resultRepository;
    private final OutpCriticalAlertRepository alertRepository;

    @EventListener
    @Transactional
    public void onLabResult(LabResultReceivedEvent event) {
        var orders = orderRepository.findByGroupNo(event.orderGroupNo()).stream()
                .filter(o -> "LAB".equals(o.getOrderType()))
                .filter(o -> "CHARGED".equals(o.getStatus()) || "EXECUTED".equals(o.getStatus()))
                .toList();
        if (orders.isEmpty()) {
            throw new IllegalStateException("未找到可回传的检验申请: " + event.orderGroupNo());
        }
        for (OutpOrder order : orders) {
            StringBuilder critical = new StringBuilder();
            for (var item : event.items()) {
                OutpLabResult r = new OutpLabResult();
                r.setOrderId(order.getId());
                r.setItemCode(item.code());
                r.setItemName(item.name());
                r.setResultValue(item.value());
                r.setUnit(item.unit());
                r.setRefRange(item.refRange());
                r.setAbnormalFlag(item.abnormalFlag());
                resultRepository.save(r);
                if (item.abnormalFlag() != null && CRITICAL_FLAGS.contains(item.abnormalFlag())) {
                    critical.append("%s=%s%s(%s) ".formatted(
                            item.name(), item.value(), item.unit() == null ? "" : item.unit(), item.abnormalFlag()));
                }
            }
            order.setStatus("EXECUTED");
            orderRepository.save(order);
            if (!critical.isEmpty()) {
                OutpCriticalAlert alert = new OutpCriticalAlert();
                alert.setOrderId(order.getId());
                alert.setRegistrationId(order.getRegistrationId());
                alert.setContent("【危急值】%s：%s".formatted(order.getItemName(), critical.toString().trim()));
                alertRepository.save(alert);
                log.warn("危急值告警: order={} {}", order.getId(), critical);
            }
        }
    }
}
