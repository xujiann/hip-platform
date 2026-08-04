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

    @Transactional
    public Patient update(Long id, Patient data) {
        Patient p = patientRepository.findById(id).orElseThrow();
        p.setName(data.getName());
        p.setSex(data.getSex());
        p.setBirthDate(data.getBirthDate());
        p.setIdType(data.getIdType());
        p.setIdNo(data.getIdNo());
        p.setPhone(data.getPhone());
        p.setAddress(data.getAddress());
        p.setInsuranceType(data.getInsuranceType());
        p.setBloodType(data.getBloodType());
        p.setAllergyHistory(data.getAllergyHistory());
        return patientRepository.save(p);
    }

    public static Integer ageOf(LocalDate birthDate) {
        return birthDate == null ? null : Period.between(birthDate, LocalDate.now()).getYears();
    }
}
