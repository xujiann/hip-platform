package cn.hip.platform.empi.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/patients")
@PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE','CASHIER','TECHNICIAN','QUALITY')")   // 1.0.9：权限清点补齐
@RequiredArgsConstructor
public class PatientController {

    private final PatientService patientService;
    private final cn.hip.platform.core.service.ConfigReader configReader;

    /** 身份证校验位校验（empi_idcard_checksum=0 可关，供存量迁移含历史假号的医院） */
    private String idCardError(Patient p) {
        if (p.getIdNo() == null || p.getIdNo().isBlank()) return null;
        if (p.getIdType() != null && !"ID_CARD".equals(p.getIdType())) return null;
        if (!"1".equals(configReader.get("empi_idcard_checksum", "1"))) return null;
        return PatientService.idCardChecksumOk(p.getIdNo()) ? null : "身份证号校验位不符";
    }

    @GetMapping
    public R<Map<String, Object>> search(@RequestParam(defaultValue = "") String keyword,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         org.springframework.security.core.Authentication auth) {
        var p = patientService.search(keyword, page, size);
        boolean admin = isAdmin(auth);
        return R.ok(Map.of(
                "total", p.getTotalElements(),
                "records", p.getContent().stream()
                        .map(x -> admin ? toDto(x) : maskSensitive(toDto(x))).toList()));
    }

    /** 等保：手机号/证件号仅 ADMIN 见明文——**所有**返回患者的出口都要过这道，
     *  否则一个空 PUT 就能绕过列表脱敏读到明文（A-3）。 */
    private boolean isAdmin(org.springframework.security.core.Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    /** 手机号保留前3后4，证件号保留前4后3 */
    private Map<String, Object> maskSensitive(Map<String, Object> dto) {
        dto.computeIfPresent("phone", (k, v) -> mask(String.valueOf(v), 3, 4));
        dto.computeIfPresent("idNo", (k, v) -> mask(String.valueOf(v), 4, 3));
        return dto;
    }

    private String mask(String s, int head, int tail) {
        if (s == null || s.isBlank() || "null".equals(s) || s.length() <= head + tail) return s;
        return s.substring(0, head) + "*".repeat(s.length() - head - tail) + s.substring(s.length() - tail);
    }

    /** 含掩码字符的值一律拒绝入库：前端把脱敏展示值原样提交会永久覆盖真实号码 */
    private String maskedValueError(Patient p) {
        if (p.getPhone() != null && p.getPhone().contains("*")) return "手机号包含掩码字符，请重新输入完整号码";
        if (p.getIdNo() != null && p.getIdNo().contains("*")) return "证件号包含掩码字符，请重新输入完整号码";
        return null;
    }

    @PostMapping
    public R<Map<String, Object>> register(@RequestBody Patient patient,
                                           org.springframework.security.core.Authentication auth) {
        if (patient.getName() == null || patient.getName().isBlank()) {
            return R.fail(2001, "患者姓名不能为空");
        }
        String maskErr = maskedValueError(patient);
        if (maskErr != null) return R.fail(2004, maskErr);
        String idErr = idCardError(patient);
        if (idErr != null) return R.fail(2003, idErr);
        if (patient.getSex() == null) patient.setSex("U");
        var dto = toDto(patientService.register(patient));
        return R.ok(isAdmin(auth) ? dto : maskSensitive(dto));
    }

    @PutMapping("/{id}")
    public R<Map<String, Object>> update(@PathVariable Long id, @RequestBody Patient patient,
                                         org.springframework.security.core.Authentication auth) {
        String maskErr = maskedValueError(patient);
        if (maskErr != null) return R.fail(2004, maskErr);
        String idErr = idCardError(patient);
        if (idErr != null) return R.fail(2003, idErr);
        var dto = toDto(patientService.update(id, patient));
        return R.ok(isAdmin(auth) ? dto : maskSensitive(dto));
    }

    private Map<String, Object> toDto(Patient p) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("id", p.getId());
        m.put("patientNo", p.getPatientNo());
        m.put("name", p.getName());
        m.put("sex", p.getSex());
        m.put("birthDate", p.getBirthDate());
        m.put("age", PatientService.ageOf(p.getBirthDate()));
        m.put("idType", p.getIdType());
        m.put("idNo", p.getIdNo());
        m.put("phone", p.getPhone());
        m.put("address", p.getAddress());
        m.put("insuranceType", p.getInsuranceType());
        m.put("bloodType", p.getBloodType());
        m.put("allergyHistory", p.getAllergyHistory());
        return m;
    }
}
