package cn.hip.outpatient.web;

import cn.hip.outpatient.entity.OutpOrder;
import cn.hip.outpatient.entity.OutpOrderReport;
import cn.hip.outpatient.repository.OutpOrderReportRepository;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.repository.OutpRegistrationRepository;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import cn.hip.platform.empi.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 医技执行站：检查/检验/治疗的执行与结果录入 */
@RestController
@RequestMapping("/api/outpatient/exec")
@PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN','NURSE')")   // 1.0.6：医技执行限技师与护士
@RequiredArgsConstructor
public class ExecStationController {

    private final OutpOrderRepository orderRepository;
    private final OutpOrderReportRepository reportRepository;
    private final OutpRegistrationRepository registrationRepository;
    private final PatientRepository patientRepository;
    private final CurrentUserService currentUserService;

    @GetMapping("/worklist")
    public R<List<Map<String, Object>>> worklist() {
        return R.ok(orderRepository.chargedExecutables().stream().map(o -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("orderId", o.getId());
            m.put("groupNo", o.getGroupNo());
            m.put("orderType", o.getOrderType());
            m.put("itemName", o.getItemName());
            m.put("qty", o.getQty());
            registrationRepository.findById(o.getRegistrationId()).ifPresent(r ->
                    patientRepository.findById(r.getPatientId()).ifPresent(p -> {
                        m.put("patientNo", p.getPatientNo());
                        m.put("patientName", p.getName());
                    }));
            return (Map<String, Object>) m;
        }).toList());
    }

    public record ExecuteRequest(String resultText) {}

    @PostMapping("/{orderId}")
    @Transactional
    public R<Object> execute(@PathVariable Long orderId, @RequestBody ExecuteRequest req, Authentication auth) {
        OutpOrder o = orderRepository.findById(orderId).orElse(null);
        if (o == null) return R.fail(7001, "医嘱不存在");
        if (!"CHARGED".equals(o.getStatus())) return R.fail(7002, "仅已收费项目可执行");
        if ("DRUG".equals(o.getOrderType()) || "REG".equals(o.getOrderType())) {
            return R.fail(7003, "药品/挂号费不在执行范围");
        }
        o.setStatus("EXECUTED");
        orderRepository.save(o);
        OutpOrderReport report = new OutpOrderReport();
        report.setOrderId(orderId);
        report.setResultText(req.resultText());
        report.setExecutorId(currentUserService.idOf(auth));
        reportRepository.save(report);
        return R.ok(Map.of("orderId", orderId, "status", "EXECUTED"));
    }

    /** 报告查询（医生站回看） */
    @GetMapping("/reports")
    public R<List<OutpOrderReport>> reports(@RequestParam Long registrationId) {
        var orderIds = orderRepository.findByRegistrationIdOrderByIdAsc(registrationId)
                .stream().map(OutpOrder::getId).toList();
        return R.ok(reportRepository.findByOrderIdIn(orderIds));
    }
}
