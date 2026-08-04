package cn.hip.platform.empi.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/patients")
@RequiredArgsConstructor
public class PatientController {

    private final PatientService patientService;

    @GetMapping
    public R<Map<String, Object>> search(@RequestParam(defaultValue = "") String keyword,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        var p = patientService.search(keyword, page, size);
        return R.ok(Map.of(
                "total", p.getTotalElements(),
                "records", p.getContent().stream().map(this::toDto).toList()));
    }

    @PostMapping
    public R<Map<String, Object>> register(@RequestBody Patient patient) {
        if (patient.getName() == null || patient.getName().isBlank()) {
            return R.fail(2001, "患者姓名不能为空");
        }
        if (patient.getSex() == null) patient.setSex("U");
        return R.ok(toDto(patientService.register(patient)));
    }

    @PutMapping("/{id}")
    public R<Map<String, Object>> update(@PathVariable Long id, @RequestBody Patient patient) {
        return R.ok(toDto(patientService.update(id, patient)));
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
