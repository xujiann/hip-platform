package cn.hip.outpatient.web;

import cn.hip.outpatient.entity.OutpDiagnosis;
import cn.hip.outpatient.entity.OutpEmr;
import cn.hip.outpatient.repository.*;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import cn.hip.platform.empi.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/outpatient/doctor")
@PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP')")   // 1.0.9：权限清点补齐
@RequiredArgsConstructor
public class DoctorStationController {

    private final DoctorStationService doctorStationService;
    private final OutpRegistrationRepository registrationRepository;
    private final OutpEmrRepository emrRepository;
    private final OutpDiagnosisRepository diagnosisRepository;
    private final OutpOrderRepository orderRepository;
    private final PatientRepository patientRepository;
    private final CurrentUserService currentUserService;
    private final cn.hip.platform.core.repository.SysUserRepository userRepository;
    private final cn.hip.platform.integration.signature.SignatureAdapter signatureAdapter;

    /**
     * 接诊队列：当日挂号（含已诊，医生可回看）。
     *
     * <p>v43 追加 emrWritten/emrSigned 两个只读标志，队列上直接标「未签」——这是诊毕未签提示
     * 的常驻可见面（本版不阻断任何写路径，只提示）。批量取病历，不做逐行 N+1。
     */
    @GetMapping("/worklist")
    public R<List<Map<String, Object>>> worklist(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        var regs = registrationRepository.findByVisitDateOrderByIdDesc(date).stream()
                .filter(r -> !"CANCELLED".equals(r.getStatus()))
                .toList();
        Map<Long, OutpEmr> emrByReg = regs.isEmpty() ? Map.of()
                : emrRepository.findByRegistrationIdIn(regs.stream().map(r -> r.getId()).toList()).stream()
                        .collect(java.util.stream.Collectors.toMap(OutpEmr::getRegistrationId, e -> e));
        return R.ok(regs.stream()
                .map(r -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("registrationId", r.getId());
                    m.put("regNo", r.getRegNo());
                    m.put("status", r.getStatus());
                    m.put("deptId", r.getDeptId());
                    OutpEmr e = emrByReg.get(r.getId());
                    m.put("emrWritten", e != null);
                    m.put("emrSigned", e != null && e.getSignature() != null);
                    patientRepository.findById(r.getPatientId()).ifPresent(p -> {
                        m.put("patientId", p.getId());
                        m.put("patientNo", p.getPatientNo());
                        m.put("patientName", p.getName());
                        m.put("sex", p.getSex());
                        m.put("age", cn.hip.platform.empi.service.PatientService.ageOf(p.getBirthDate()));
                        m.put("allergyHistory", p.getAllergyHistory());
                    });
                    return (Map<String, Object>) m;
                }).toList());
    }

    @PostMapping("/{registrationId}/start")
    public R<Map<String, Object>> start(@PathVariable Long registrationId, Authentication auth) {
        try {
            var reg = doctorStationService.startVisit(registrationId, currentUserService.idOf(auth));
            return R.ok(Map.of("registrationId", reg.getId(), "status", reg.getStatus()));
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /**
     * 病历+诊断+医嘱一次取全（工作区加载）。
     *
     * <p>v43 追加 emrSignerName：签名冻结态要在页面上写明「谁签的」，
     * 只加这一个附加键，emr 本体形状与既有三键顺序一律不动。
     */
    @GetMapping("/{registrationId}/workspace")
    public R<Map<String, Object>> workspace(@PathVariable Long registrationId) {
        var m = new LinkedHashMap<String, Object>();
        OutpEmr emr = emrRepository.findByRegistrationId(registrationId).orElse(null);
        m.put("emr", emr);
        m.put("emrSignerName", emr == null || emr.getSignature() == null || emr.getDoctorId() == null ? null
                : userRepository.findById(emr.getDoctorId())
                        .map(cn.hip.platform.core.entity.SysUser::getRealName).orElse(null));
        m.put("diagnoses", diagnosisRepository.findByRegistrationIdOrderByPrimaryDiagDescIdAsc(registrationId));
        m.put("orders", orderRepository.findByRegistrationIdOrderByIdAsc(registrationId));
        return R.ok(m);
    }

    /**
     * 病历保存请求体。
     *
     * <p>v45 车道 I 追加两个<b>可空</b>参数 {@code templateId} / {@code fields}（989★/1075★）——
     * <b>刻意不新建"结构化病历"端点</b>：另开一条写路径就等于把签名冻结（4008/4010）、
     * 诊断整表替换、常用诊断累加、CDSS 触发这几件事各维护两份，迟早漂移。
     *
     * <p>旧请求体（只有 emr + diagnoses）经 Jackson 反序列化后两个新字段为 null，
     * 服务层整段短路，行为逐字节不变；两参构造器保留是为让既有单测/E2E 源码零改动。
     */
    public record SaveEmrRequest(OutpEmr emr, List<OutpDiagnosis> diagnoses,
                                 Long templateId, Map<String, Object> fields) {
        /** v45 之前的两参形态（V42/V43/V44 三批既有单测与前端旧版本按此调用） */
        public SaveEmrRequest(OutpEmr emr, List<OutpDiagnosis> diagnoses) {
            this(emr, diagnoses, null, null);
        }
    }

    @PutMapping("/{registrationId}/emr")
    public R<OutpEmr> saveEmr(@PathVariable Long registrationId, @RequestBody SaveEmrRequest req,
                              Authentication auth) {
        try {
            return R.ok(doctorStationService.saveEmr(registrationId, req.emr(),
                    req.diagnoses() == null ? List.of() : req.diagnoses(), currentUserService.idOf(auth),
                    req.templateId(), req.fields()));
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /**
     * 门诊病历 CA 签名（签名即冻结原文，之后只能走 /emr/amend 补正）。
     *
     * <p>端点自 1.0 起就在，v43 车道A 之前前端没有任何签名按钮，正常路径走不到——
     * 连带使「已签名才显示」的补正区块永远不可达。本版只补入口与两条缺失校验
     * （4022 空病历 / 4023 非书写医师），<b>成功返回体 {signature, signedAt} 保持不变</b>。
     */
    @PostMapping("/{registrationId}/emr/sign")
    public R<Object> signEmr(@PathVariable Long registrationId, Authentication auth) {
        try {
            var emr = doctorStationService.signEmr(registrationId, currentUserService.idOf(auth), signatureAdapter);
            return R.ok(Map.of("signature", emr.getSignature(), "signedAt", emr.getSignedAt()));
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    public record AmendRequest(String amendText, String reason) {}

    /** 病历补正（阻塞4）：签名冻结病历追加法定留痕补正，不改原文 */
    @PostMapping("/{registrationId}/emr/amend")
    public R<Void> amendEmr(@PathVariable Long registrationId, @RequestBody AmendRequest req, Authentication auth) {
        try {
            doctorStationService.amendEmr(registrationId, req.amendText(), req.reason(),
                    currentUserService.idOf(auth));
            return R.ok();
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 病历补正历史 */
    @GetMapping("/{registrationId}/emr/amendments")
    public R<List<Map<String, Object>>> amendments(@PathVariable Long registrationId) {
        return R.ok(doctorStationService.listAmendments(registrationId));
    }

    public record CreateOrdersRequest(List<DoctorStationService.OrderLine> lines) {}

    @PostMapping("/{registrationId}/orders")
    public R<Object> createOrders(@PathVariable Long registrationId, @RequestBody CreateOrdersRequest req,
                                  Authentication auth) {
        try {
            return R.ok(doctorStationService.createOrders(registrationId, req.lines(), currentUserService.idOf(auth)));
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @PutMapping("/orders/{orderId}/cancel")
    public R<Void> cancelOrder(@PathVariable Long orderId) {
        try {
            doctorStationService.cancelOrder(orderId);
            return R.ok();
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    // ==================== v44 车道E：门诊诊断域完整化（977/979/982/983/984/1084） ====================

    /**
     * 诊断助手（偏离表 979★）：一次返回 <b>历史 / 常用 / 高频</b> 三段，供诊断录入区点选带入。
     *
     * <p>三段各限 20 条、各一条聚合 SQL，无 N+1；frequent 段按 outp_diagnosis 真实数据聚合，
     * 不是硬编码常见病清单。keyword 可空（空则不加过滤）。纯只读。
     */
    @GetMapping("/diagnosis-assist")
    public R<Map<String, Object>> diagnosisAssist(@RequestParam(required = false) Long patientId,
                                                  @RequestParam(required = false) String keyword,
                                                  Authentication auth) {
        return R.ok(doctorStationService.diagnosisAssist(patientId, keyword, currentUserService.idOf(auth)));
    }

    public record FavoriteRequest(String icdCode, String icdName, String diagSystem) {}

    /**
     * 手动加星到常用诊断（偏离表 1084★「存储常用诊断」）。
     * 保存病历时已自动累加，本端点是医生主动收藏的入口。
     */
    @PostMapping("/diagnosis-favorite")
    public R<Void> addFavorite(@RequestBody FavoriteRequest req, Authentication auth) {
        doctorStationService.upsertFavorite(currentUserService.idOf(auth),
                req.icdCode(), req.icdName(), req.diagSystem());
        return R.ok();
    }

    /** 删除一条个人常用诊断（只能删自己的） */
    @DeleteMapping("/diagnosis-favorite/{favoriteId}")
    public R<Void> deleteFavorite(@PathVariable Long favoriteId, Authentication auth) {
        doctorStationService.deleteFavorite(currentUserService.idOf(auth), favoriteId);
        return R.ok();
    }

    /** 日期走 JSON body，由 Jackson JSR-310 按 ISO 解析（同 InventoryController.StockInRequest 口径） */
    public record SpecialDiseaseRequest(Long patientId, String diseaseCode, String diseaseName,
                                        String insuranceType, LocalDate startDate, LocalDate endDate,
                                        String remark) {}

    /**
     * 医保特殊病种（慢特病）<b>院内登记</b>——偏离表 984★。
     *
     * <p><b>做到哪儿、没做哪儿，写在这里防止后来人误读</b>：本组端点只做院内登记
     * （记下患者已认定的特殊病种与院内认定有效期，诊断/开单界面据此提示），
     * <b>不含向医保经办机构备案报送</b>——报送要走当地医保接口（报文规范 + CA + 专网），
     * 属外部条件。本仓不提供、也不留假的"报送/审批"状态。
     */
    @PostMapping("/special-disease")
    public R<Object> addSpecialDisease(@RequestBody SpecialDiseaseRequest req, Authentication auth) {
        try {
            Long id = doctorStationService.addSpecialDisease(req.patientId(), req.diseaseCode(),
                    req.diseaseName(), req.insuranceType(), req.startDate(), req.endDate(),
                    req.remark(), currentUserService.idOf(auth));
            return R.ok(Map.of("id", id));
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 患者的特殊病种院内登记；activeOnly=true 只返回业务日期仍在有效期内的（开单提示用） */
    @GetMapping("/special-disease")
    public R<List<Map<String, Object>>> specialDiseases(@RequestParam Long patientId,
                                                        @RequestParam(defaultValue = "false") boolean activeOnly) {
        return R.ok(doctorStationService.listSpecialDiseases(patientId, activeOnly));
    }

    /** 删除一条特殊病种院内登记 */
    @DeleteMapping("/special-disease/{id}")
    public R<Void> deleteSpecialDisease(@PathVariable Long id) {
        doctorStationService.deleteSpecialDisease(id);
        return R.ok();
    }

    /**
     * v37 门诊病历连续调阅：患者历次就诊（近 50 次，剔除已退号）+ 各次诊断与病历摘要。
     * 医生站接诊时看既往——此前 findTop50ByPatientIdOrderByIdDesc 定义后零调用者，本端点接上。
     * 批量 In 查询避免 50 次 N+1；纯只读。
     */
    @GetMapping("/patient/{patientId}/history")
    public R<List<Map<String, Object>>> patientHistory(@PathVariable Long patientId) {
        var regs = registrationRepository.findTop50ByPatientIdOrderByIdDesc(patientId).stream()
                .filter(r -> !"CANCELLED".equals(r.getStatus()))
                .toList();
        if (regs.isEmpty()) return R.ok(List.of());
        var regIds = regs.stream().map(r -> r.getId()).toList();
        var emrByReg = emrRepository.findByRegistrationIdIn(regIds).stream()
                .collect(java.util.stream.Collectors.toMap(e -> e.getRegistrationId(), e -> e));
        var diagByReg = diagnosisRepository.findByRegistrationIdIn(regIds).stream()
                .collect(java.util.stream.Collectors.groupingBy(d -> d.getRegistrationId()));
        return R.ok(regs.stream().map(r -> {
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("registrationId", r.getId());
            m.put("visitDate", r.getVisitDate());
            m.put("status", r.getStatus());
            var emr = emrByReg.get(r.getId());
            if (emr != null) {
                m.put("chiefComplaint", emr.getChiefComplaint());
                m.put("advice", emr.getAdvice());
                m.put("signed", emr.getSignature() != null);
            }
            m.put("diagnoses", diagByReg.getOrDefault(r.getId(), List.of()).stream().map(d -> Map.of(
                    "icdCode", d.getIcdCode() == null ? "" : d.getIcdCode(),
                    "icdName", d.getIcdName() == null ? "" : d.getIcdName(),
                    "primaryDiag", Boolean.TRUE.equals(d.getPrimaryDiag()))).toList());
            return (Map<String, Object>) m;
        }).toList());
    }
}
