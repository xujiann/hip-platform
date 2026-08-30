package cn.hip.server;

import cn.hip.inpatient.web.DiagnosisController;
import cn.hip.inpatient.service.InpatientService;
import cn.hip.platform.core.entity.SysUser;
import cn.hip.platform.core.repository.SysUserRepository;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v32 收口回归：住院诊断补录的对象级归属校验（9018）。
 *
 * <p>DiagnosisController 类级放行 ADMIN/DOCTOR_OUTP/QUALITY，此前 add() 只校验 admission 存在——
 * 门诊医生 DOCTOR_OUTP 可给**任意**住院病案补录影响 DRG 权重的诊断（水平越权）。v32 补：
 * ADMIN/QUALITY（编码组）横切所有病案；仅具 DOCTOR_OUTP 的医生只能补录主管为本人的病案；
 * doctor_id 未采集时放行（null 容忍，不误拒历史/测试数据）。
 *
 * <p>直调 controller bean（AOP 代理）会执行类级 @PreAuthorize，故每次调用前把安全上下文置为
 * 对应角色；同时把该 Authentication 作为 add() 的入参（等价 Spring MVC 的注入），驱动归属逻辑。
 */
@SpringBootTest
@Transactional
class V32HardeningTest {

    @Autowired DiagnosisController diagnosisController;
    @Autowired InpatientService inpatientService;
    @Autowired PatientService patientService;
    @Autowired SysUserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private Long newDoctor(String username) {
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("Abcd1234"));
        u.setRealName(username);
        return userRepository.save(u).getId();
    }

    private Authentication actAs(String username, String role) {
        var auth = new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
        return auth;
    }

    private Long newPatient(String name) {
        Patient p = new Patient();
        p.setName(name);
        p.setSex("M");
        return patientService.register(p).getId();
    }

    @Test
    void diagnosisAddEnforcesOwnershipForOutpatientDoctor() {
        String docAName = "v32docA" + System.nanoTime();
        String docBName = "v32docB" + System.nanoTime();
        Long docA = newDoctor(docAName);                        // 主管医生
        newDoctor(docBName);                                    // 非主管的门诊医生
        Long pid = newPatient("归属校验v32");
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        Long admId = inpatientService.admit(pid, 1L, bedId, docA, "J18.9", "肺炎",
                new BigDecimal("100"), "CASH", null).getId();
        entityManager.flush();

        // 乙（非本人主管）补录 → 9018（不同 ICD 避免与后续成功用例撞唯一键 9015）
        var authB = actAs(docBName, "DOCTOR_OUTP");
        assertEquals(9018, diagnosisController.add(
                new DiagnosisController.DiagReq(admId, "I10", "高血压"), authB).getCode(),
                "非本人主管的门诊医生不得补录");

        // 甲（本人主管）补录 → ok
        var authA = actAs(docAName, "DOCTOR_OUTP");
        assertEquals(0, diagnosisController.add(
                new DiagnosisController.DiagReq(admId, "E11", "糖尿病"), authA).getCode(),
                "本人主管可补录");

        // ADMIN 横切 → ok
        var authAdmin = actAs("admin", "ADMIN");
        assertEquals(0, diagnosisController.add(
                new DiagnosisController.DiagReq(admId, "N18", "慢性肾病"), authAdmin).getCode(),
                "ADMIN 横切所有病案");

        // doctor_id 置空（未采集主管）→ DOCTOR_OUTP 放行（null 容忍）
        jdbc.update("update inp_admission set doctor_id = null where id = ?", admId);
        var authB2 = actAs(docBName, "DOCTOR_OUTP");
        assertEquals(0, diagnosisController.add(
                new DiagnosisController.DiagReq(admId, "K35", "阑尾炎"), authB2).getCode(),
                "主管未采集时放行，不误拒");
    }
}
