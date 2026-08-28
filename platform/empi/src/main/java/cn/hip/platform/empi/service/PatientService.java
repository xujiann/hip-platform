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
import cn.hip.platform.core.config.BusinessDates;

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
        return update(id, data, true);
    }

    /**
     * @param canEditIdentity 是否允许修改身份与联系方式（姓名/证件/手机）。
     *   上线前审查 P2-2：患者端登录用 patientNo+phone，任意院内角色改掉手机号即可登入他人
     *   门户读其报告/账单；改证件/姓名是身份冒用。故非管理员**只能改临床与地址类字段**，
     *   身份三项若发生实际变更即拒绝。临床字段（血型/过敏史）仍放开——医生护士要合法维护。
     */
    public Patient update(Long id, Patient data, boolean canEditIdentity) {
        Patient p = patientRepository.findById(id).orElseThrow();
        if (!canEditIdentity && identityChanged(p, data)) {
            throw new cn.hip.platform.core.common.HipBizException(2005,
                    "修改患者姓名/证件/手机需管理员权限");
        }
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

    /** 身份三项（姓名/证件类型+号/手机）是否被实际改动——同值提交不算改动，避免误伤合法编辑 */
    private boolean identityChanged(Patient cur, Patient data) {
        return changed(data.getName(), cur.getName())
                || changed(data.getIdType(), cur.getIdType())
                || changed(data.getIdNo(), cur.getIdNo())
                || changed(data.getPhone(), cur.getPhone());
    }

    private boolean changed(String incoming, String current) {
        return incoming != null && !incoming.equals(current);
    }

    public static Integer ageOf(LocalDate birthDate) {
        return birthDate == null ? null : Period.between(birthDate, BusinessDates.today()).getYears();
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
