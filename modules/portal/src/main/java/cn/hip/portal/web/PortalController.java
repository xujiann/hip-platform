package cn.hip.portal.web;

import cn.hip.outpatient.repository.OutpChargeRepository;
import cn.hip.outpatient.repository.OutpLabResultRepository;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.repository.OutpRegistrationRepository;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.repository.SysDeptRepository;
import cn.hip.platform.core.security.JwtService;
import cn.hip.platform.empi.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 患者端 H5 接口。登录后令牌主体为 portal:{patientId}，只授予 ROLE_PORTAL，
 * 所有查询强制以令牌内 patientId 为准（患者只能看自己的数据）。
 */
@RestController
@RequestMapping("/api/portal")
@RequiredArgsConstructor
public class PortalController {

    private final PatientRepository patientRepository;
    private final JwtService jwtService;
    private final RegistrationService registrationService;
    private final OutpRegistrationRepository registrationRepository;
    private final OutpScheduleRepository scheduleRepository;
    private final OutpOrderRepository orderRepository;
    private final OutpLabResultRepository labResultRepository;
    private final OutpChargeRepository chargeRepository;
    private final SysDeptRepository deptRepository;

    public record PortalLoginRequest(String patientNo, String phone) {}

    /** MVP 以患者号+建档手机号认证；生产环境替换为电子健康卡/微信实名 */
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody PortalLoginRequest req) {
        var patient = patientRepository.findByPatientNo(req.patientNo()).orElse(null);
        if (patient == null || patient.getPhone() == null || !patient.getPhone().equals(req.phone())) {
            return R.fail(9501, "患者号或手机号不正确");
        }
        String token = jwtService.issue("portal:" + patient.getId());
        return R.ok(Map.of("token", token, "patientName", patient.getName(), "patientNo", patient.getPatientNo()));
    }

    private Long patientId(Authentication auth) {
        return Long.valueOf(auth.getName().substring("portal:".length()));
    }

    @GetMapping("/my/registrations")
    public R<List<Map<String, Object>>> myRegistrations(Authentication auth) {
        Long pid = patientId(auth);
        return R.ok(registrationRepository.findTop50ByPatientIdOrderByIdDesc(pid).stream().map(r -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", r.getId());
            m.put("visitDate", r.getVisitDate());
            m.put("regNo", r.getRegNo());
            m.put("deptName", deptRepository.findById(r.getDeptId()).map(d -> d.getName()).orElse(""));
            m.put("fee", r.getFee());
            m.put("status", r.getStatus());
            return (Map<String, Object>) m;
        }).toList());
    }

    @GetMapping("/my/lab-reports")
    public R<List<Map<String, Object>>> myLabReports(Authentication auth) {
        Long pid = patientId(auth);
        return R.ok(registrationRepository.findTop50ByPatientIdOrderByIdDesc(pid).stream()
                .flatMap(r -> orderRepository
                        .findByRegistrationIdAndOrderTypeAndStatusOrderByIdAsc(r.getId(), "LAB", "EXECUTED").stream())
                .map(o -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("orderId", o.getId());
                    m.put("itemName", o.getItemName());
                    m.put("reportDate", o.getCreatedAt());
                    m.put("results", labResultRepository.findByOrderIdOrderByIdAsc(o.getId()));
                    return (Map<String, Object>) m;
                }).toList());
    }

    @GetMapping("/my/charges")
    public R<List<Map<String, Object>>> myCharges(Authentication auth) {
        Long pid = patientId(auth);
        return R.ok(registrationRepository.findTop50ByPatientIdOrderByIdDesc(pid).stream()
                .flatMap(r -> chargeRepository.findByRegistrationIdOrderByIdDesc(r.getId()).stream())
                .map(c -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("chargeNo", c.getChargeNo());
                    m.put("totalAmount", c.getTotalAmount());
                    m.put("payMethod", c.getPayMethod());
                    m.put("status", c.getStatus());
                    m.put("createdAt", c.getCreatedAt());
                    return (Map<String, Object>) m;
                }).toList());
    }

    /** 可约号源（今日起） */
    @GetMapping("/schedules")
    public R<List<Map<String, Object>>> schedules(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return R.ok(scheduleRepository.findByScheduleDateAndEnabledTrueOrderByDeptIdAscIdAsc(date).stream()
                .map(s -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("id", s.getId());
                    m.put("deptName", deptRepository.findById(s.getDeptId()).map(d -> d.getName()).orElse(""));
                    m.put("regType", s.getRegType());
                    m.put("shift", s.getShift());
                    m.put("fee", s.getFee());
                    m.put("remaining", s.getCapacity() - s.getBooked());
                    return (Map<String, Object>) m;
                }).toList());
    }

    public record PortalRegisterRequest(Long scheduleId) {}

    /** 在线预约挂号（复用院内挂号服务，同一防超挂逻辑） */
    @PostMapping("/register")
    public R<Object> register(@RequestBody PortalRegisterRequest req, Authentication auth) {
        try {
            var reg = registrationService.register(patientId(auth), req.scheduleId());
            return R.ok(Map.of("regNo", reg.getRegNo(), "visitDate", reg.getVisitDate().toString(),
                    "fee", reg.getFee(), "deptName",
                    deptRepository.findById(reg.getDeptId()).map(d -> d.getName()).orElse("")));
        } catch (RegistrationService.BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }
}
