package cn.hip.platform.empi.service;

import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Period;

@Service
@RequiredArgsConstructor
public class PatientService {

    private final PatientRepository patientRepository;

    public Page<Patient> search(String keyword, int page, int size) {
        return patientRepository.search(keyword, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
    }

    /**
     * 建档。身份证号已存在时返回已有档案（幂等，避免重复建档）。
     */
    @Transactional
    public Patient register(Patient p) {
        if (p.getIdNo() != null && !p.getIdNo().isBlank()) {
            var existing = patientRepository.findByIdTypeAndIdNo(
                    p.getIdType() == null ? "ID_CARD" : p.getIdType(), p.getIdNo());
            if (existing.isPresent()) {
                return existing.get();
            }
            // 身份证号可推算出生日期与性别
            if ("ID_CARD".equals(p.getIdType()) && p.getIdNo().length() == 18) {
                if (p.getBirthDate() == null) {
                    p.setBirthDate(LocalDate.parse(p.getIdNo().substring(6, 14),
                            java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
                }
                if (p.getSex() == null || "U".equals(p.getSex())) {
                    int seq = p.getIdNo().charAt(16) - '0';
                    p.setSex(seq % 2 == 1 ? "M" : "F");
                }
            }
        }
        Patient saved = patientRepository.save(p);
        saved.setPatientNo("P%08d".formatted(saved.getId()));
        return patientRepository.save(saved);
    }

    /**
     * 更新：**仅覆盖请求中出现（非 null）的字段**。
     * 曾为全字段覆盖——前端少传一个字段就会静默清空过敏史/血型/医保类型，
     * 而过敏史是合理用药过敏拦截的数据源，丢失即临床风险。
     * 需要清空某字段时传空串（受 blank 判定的字段）或走专用接口。
     */
    @Transactional
    public Patient update(Long id, Patient data) {
        Patient p = patientRepository.findById(id).orElseThrow();
        if (data.getName() != null) p.setName(data.getName());
        if (data.getSex() != null) p.setSex(data.getSex());
        if (data.getBirthDate() != null) p.setBirthDate(data.getBirthDate());
        if (data.getIdType() != null) p.setIdType(data.getIdType());
        if (data.getIdNo() != null) p.setIdNo(data.getIdNo());
        if (data.getPhone() != null) p.setPhone(data.getPhone());
        if (data.getAddress() != null) p.setAddress(data.getAddress());
        if (data.getInsuranceType() != null) p.setInsuranceType(data.getInsuranceType());
        if (data.getBloodType() != null) p.setBloodType(data.getBloodType());
        if (data.getAllergyHistory() != null) p.setAllergyHistory(data.getAllergyHistory());
        return patientRepository.save(p);
    }

    public static Integer ageOf(LocalDate birthDate) {
        return birthDate == null ? null : Period.between(birthDate, LocalDate.now()).getYears();
    }

    /** GB 11643 十八位身份证校验位（加权模 11） */
    public static boolean idCardChecksumOk(String idNo) {
        if (idNo == null || !idNo.matches("\\d{17}[0-9Xx]")) {
            return false;
        }
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (idNo.charAt(i) - '0') * weights[i];
        }
        return Character.toUpperCase(idNo.charAt(17)) == "10X98765432".charAt(sum % 11);
    }
}
