package cn.hip.outpatient.service;

import cn.hip.outpatient.entity.*;
import cn.hip.outpatient.repository.*;
import cn.hip.platform.masterdata.entity.ChargeItem;
import cn.hip.platform.masterdata.entity.DrugItem;
import cn.hip.platform.masterdata.repository.ChargeItemRepository;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import cn.hip.outpatient.service.RegistrationService.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
public class DoctorStationService {

    private final OutpRegistrationRepository registrationRepository;
    private final OutpEmrRepository emrRepository;
    private final OutpDiagnosisRepository diagnosisRepository;
    private final OutpOrderRepository orderRepository;
    private final DrugItemRepository drugRepository;
    private final ChargeItemRepository chargeItemRepository;
    private final cn.hip.platform.empi.repository.PatientRepository patientRepository;

    private final AtomicLong groupSeq = new AtomicLong(System.currentTimeMillis() % 100000);

    /** 接诊：挂号状态置为 VISITED */
    @Transactional
    public OutpRegistration startVisit(Long registrationId, Long doctorId) {
        OutpRegistration reg = registrationRepository.findById(registrationId)
                .orElseThrow(() -> new BizException(4001, "挂号记录不存在"));
        if ("CANCELLED".equals(reg.getStatus())) {
            throw new BizException(4002, "该号已退，不能接诊");
        }
        if ("REGISTERED".equals(reg.getStatus()) || "CALLED".equals(reg.getStatus())) {
            reg.setStatus("VISITED");
            if (reg.getDoctorId() == null) {
                reg.setDoctorId(doctorId);
            }
            registrationRepository.save(reg);
        }
        return reg;
    }

    /** 保存病历与诊断（诊断整表替换，第一条为主诊断）；已签名病历冻结不可改 */
    @Transactional
    public OutpEmr saveEmr(Long registrationId, OutpEmr data, List<OutpDiagnosis> diagnoses, Long doctorId) {
        OutpEmr emr = emrRepository.findByRegistrationId(registrationId).orElseGet(() -> {
            OutpEmr e = new OutpEmr();
            e.setRegistrationId(registrationId);
            return e;
        });
        if (emr.getSignature() != null) {
            throw new BizException(4008, "病历已签名冻结，不可修改");
        }
        emr.setChiefComplaint(data.getChiefComplaint());
        emr.setPresentIllness(data.getPresentIllness());
        emr.setPastHistory(data.getPastHistory());
        emr.setPhysicalExam(data.getPhysicalExam());
        emr.setAdvice(data.getAdvice());
        emr.setDoctorId(doctorId);
        emr = emrRepository.save(emr);

        diagnosisRepository.deleteByRegistrationId(registrationId);
        for (int i = 0; i < diagnoses.size(); i++) {
            OutpDiagnosis d = diagnoses.get(i);
            d.setId(null);
            d.setRegistrationId(registrationId);
            d.setPrimaryDiag(i == 0);
            diagnosisRepository.save(d);
        }
        return emr;
    }

    public record OrderLine(String orderType, Long itemId, Integer qty,
                            String usageRoute, String frequency, String dosePerTime, Integer days) {}

    /** 开立一组医嘱（药品成一张处方，检查检验各自成申请单） */
    @Transactional
    public List<OutpOrder> createOrders(Long registrationId, List<OrderLine> lines, Long doctorId) {
        OutpRegistration reg = registrationRepository.findById(registrationId)
                .orElseThrow(() -> new BizException(4001, "挂号记录不存在"));
        if (!"VISITED".equals(reg.getStatus())) {
            throw new BizException(4003, "请先接诊后再开单");
        }
        String stamp = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String drugGroupNo = "CF" + stamp + "-" + groupSeq.incrementAndGet();

        // 合理用药前置拦截（过敏禁忌 / 同诊重复用药）
        for (OrderLine line : lines) {
            if ("DRUG".equals(line.orderType())) {
                drugRepository.findById(line.itemId())
                        .ifPresent(drug -> checkRationalDrugUse(reg, drug.getName(), drug.getId()));
            }
        }

        return lines.stream().map(line -> {
            OutpOrder o = new OutpOrder();
            o.setRegistrationId(registrationId);
            o.setOrderType(line.orderType());
            o.setQty(line.qty() == null || line.qty() <= 0 ? 1 : line.qty());
            o.setDoctorId(doctorId);
            if ("DRUG".equals(line.orderType())) {
                DrugItem drug = drugRepository.findById(line.itemId())
                        .orElseThrow(() -> new BizException(4004, "药品不存在: " + line.itemId()));
                o.setGroupNo(drugGroupNo);
                o.setItemId(drug.getId());
                o.setItemCode(drug.getCode());
                o.setItemName(drug.getName());
                o.setSpec(drug.getSpec());
                o.setUnit(drug.getUnit());
                o.setUnitPrice(drug.getPrice());
                o.setUsageRoute(line.usageRoute());
                o.setFrequency(line.frequency());
                o.setDosePerTime(line.dosePerTime());
                o.setDays(line.days());
            } else {
                ChargeItem item = chargeItemRepository.findById(line.itemId())
                        .orElseThrow(() -> new BizException(4005, "收费项目不存在: " + line.itemId()));
                o.setGroupNo("SQ" + stamp + "-" + groupSeq.incrementAndGet());
                o.setItemId(item.getId());
                o.setItemCode(item.getCode());
                o.setItemName(item.getName());
                o.setUnit(item.getUnit());
                o.setUnitPrice(item.getPrice());
            }
            o.setAmount(o.getUnitPrice().multiply(BigDecimal.valueOf(o.getQty())));
            return orderRepository.save(o);
        }).toList();
    }

    /** 过敏交叉映射：过敏史关键词 → 命中的药名特征 */
    private static final java.util.Map<String, List<String>> ALLERGY_CROSS = java.util.Map.of(
            "青霉素", List.of("西林", "青霉素"),
            "头孢", List.of("头孢"),
            "磺胺", List.of("磺胺"),
            "阿司匹林", List.of("阿司匹林", "水杨酸"));

    /** 合理用药规则：1) 过敏史禁忌 2) 同一次就诊重复开同一药品 */
    private void checkRationalDrugUse(OutpRegistration reg, String drugName, Long drugId) {
        var patient = patientRepository.findById(reg.getPatientId()).orElse(null);
        String allergy = patient == null ? null : patient.getAllergyHistory();
        if (allergy != null && !allergy.isBlank()) {
            for (var entry : ALLERGY_CROSS.entrySet()) {
                if (allergy.contains(entry.getKey())
                        && entry.getValue().stream().anyMatch(drugName::contains)) {
                    throw new BizException(4012,
                            "过敏禁忌拦截：患者过敏史含「%s」，禁用 %s".formatted(entry.getKey(), drugName));
                }
            }
            if (allergy.contains(drugName)) {
                throw new BizException(4012, "过敏禁忌拦截：患者过敏史记载 " + drugName);
            }
        }
        boolean duplicated = orderRepository.findByRegistrationIdOrderByIdAsc(reg.getId()).stream()
                .anyMatch(o -> "DRUG".equals(o.getOrderType()) && o.getItemId().equals(drugId)
                        && !"CANCELLED".equals(o.getStatus()));
        if (duplicated) {
            throw new BizException(4013, "重复用药拦截：本次就诊已开过 " + drugName);
        }
    }

    /** 病历签名：内容摘要签名后冻结 */
    @Transactional
    public OutpEmr signEmr(Long registrationId, Long doctorId,
                           cn.hip.platform.integration.signature.SignatureAdapter signatureAdapter) {
        OutpEmr emr = emrRepository.findByRegistrationId(registrationId)
                .orElseThrow(() -> new BizException(4009, "病历不存在，请先书写保存"));
        if (emr.getSignature() != null) {
            throw new BizException(4010, "病历已签名");
        }
        String content = String.join("|",
                String.valueOf(emr.getChiefComplaint()), String.valueOf(emr.getPresentIllness()),
                String.valueOf(emr.getPastHistory()), String.valueOf(emr.getPhysicalExam()),
                String.valueOf(emr.getAdvice()));
        var result = signatureAdapter.sign(content, doctorId);
        if (!result.ok()) {
            throw new BizException(4011, "签名失败: " + result.message());
        }
        emr.setSignature(result.signature());
        emr.setSignedAt(java.time.Instant.now());
        return emrRepository.save(emr);
    }

    /** 作废医嘱（仅未收费的可作废） */
    @Transactional
    public void cancelOrder(Long orderId) {
        OutpOrder o = orderRepository.findById(orderId)
                .orElseThrow(() -> new BizException(4006, "医嘱不存在"));
        if (!"CREATED".equals(o.getStatus())) {
            throw new BizException(4007, "已收费/已执行的医嘱不能作废，请先退费");
        }
        o.setStatus("CANCELLED");
        orderRepository.save(o);
    }
}
