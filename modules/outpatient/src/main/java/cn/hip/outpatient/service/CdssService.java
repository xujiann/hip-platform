package cn.hip.outpatient.service;

import cn.hip.outpatient.entity.OutpOrder;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.empi.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import cn.hip.platform.core.config.BusinessDates;

/** 二十八期：CDSS 处方前置审查——药物相互作用(DDI)/疗程上限/年龄限制；FORBID 拦截、CAUTION 留痕提醒 */
@Service
@RequiredArgsConstructor
public class CdssService {

    private final JdbcTemplate jdbc;
    private final OutpOrderRepository orderRepository;
    private final PatientRepository patientRepository;

    public record DrugLine(String drugName, Integer days) {}

    /** 开单前审查：本次新开药品 vs 同诊全部在用药品 */
    public void checkPrescription(Long registrationId, Long patientId, List<DrugLine> newDrugs) {
        if (newDrugs.isEmpty()) return;
        List<String> existing = orderRepository.findByRegistrationIdOrderByIdAsc(registrationId).stream()
                .filter(o -> "DRUG".equals(o.getOrderType()) && !"CANCELLED".equals(o.getStatus()))
                .map(OutpOrder::getItemName)
                .toList();
        List<String> all = new ArrayList<>(existing);
        newDrugs.forEach(d -> all.add(d.drugName()));

        // DDI：新开药与全清单两两核对
        var ddiRules = jdbc.queryForList("select * from cdss_ddi_rule");
        for (DrugLine nd : newDrugs) {
            for (String other : all) {
                if (other.equals(nd.drugName())) continue;
                for (var r : ddiRules) {
                    String a = (String) r.get("drug_a"), b = (String) r.get("drug_b");
                    boolean hit = (nd.drugName().contains(a) && other.contains(b))
                            || (nd.drugName().contains(b) && other.contains(a));
                    if (!hit) continue;
                    String msg = "【相互作用】%s + %s：%s".formatted(nd.drugName(), other, r.get("message"));
                    if ("FORBID".equals(r.get("severity"))) {
                        throw new BizException(4015, "CDSS 拦截 " + msg);
                    }
                    alert(registrationId, "DDI", "CAUTION", msg);
                }
            }
        }

        // 疗程上限：CAUTION 留痕不拦截
        var doseRules = jdbc.queryForList("select * from cdss_dose_rule");
        for (DrugLine nd : newDrugs) {
            if (nd.days() == null) continue;
            for (var r : doseRules) {
                if (nd.drugName().contains((String) r.get("drug_keyword"))
                        && nd.days() > ((Number) r.get("max_days")).intValue()) {
                    alert(registrationId, "DOSE", "CAUTION",
                            "【疗程】%s 开具 %d 天：%s".formatted(nd.drugName(), nd.days(), r.get("message")));
                }
            }
        }

        // 年龄限制：FORBID 拦截（无出生日期则跳过）
        LocalDate birth = patientRepository.findById(patientId)
                .map(p -> p.getBirthDate()).orElse(null);
        if (birth != null) {
            int age = Period.between(birth, BusinessDates.today()).getYears();
            var ageRules = jdbc.queryForList("select * from cdss_age_rule");
            for (DrugLine nd : newDrugs) {
                for (var r : ageRules) {
                    if (!nd.drugName().contains((String) r.get("drug_keyword"))) continue;
                    Integer min = r.get("min_age") == null ? null : ((Number) r.get("min_age")).intValue();
                    Integer max = r.get("max_age") == null ? null : ((Number) r.get("max_age")).intValue();
                    if ((min != null && age < min) || (max != null && age > max)) {
                        throw new BizException(4017, "CDSS 拦截【年龄】%s（患者 %d 岁）：%s"
                                .formatted(nd.drugName(), age, r.get("message")));
                    }
                }
            }
        }
    }

    private void alert(Long registrationId, String type, String severity, String message) {
        jdbc.update("insert into cdss_alert(registration_id, rule_type, severity, message) values (?,?,?,?)",
                registrationId, type, severity, message);
    }
}
