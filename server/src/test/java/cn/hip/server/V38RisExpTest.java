package cn.hip.server;

import cn.hip.medtech.web.MedTechController;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** v38 RIS 体验轮：到检登记态 / 结果互认提醒 / 报告模板 type 过滤。 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class V38RisExpTest {

    @Autowired MedTechController medTech;
    @Autowired PatientService patientService;
    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired ChargeService chargeService;
    @Autowired cn.hip.outpatient.repository.OutpScheduleRepository scheduleRepository;
    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;

    private final Authentication admin = new UsernamePasswordAuthenticationToken("admin", null, List.of());

    private record ExamCase(Long patientId, Long orderId, Long examId) {}

    private ExamCase examCase() {
        Patient p = new Patient();
        p.setName("RIS体验" + System.nanoTime());
        p.setSex("F");
        Long pid = patientService.register(p).getId();
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        Long rid = registrationService.register(pid, s.getId()).getId();
        doctorStationService.startVisit(rid, null);
        Long examItem = seeds.chargeItem("心电图", "EXAM").getId();
        var order = doctorStationService.createOrders(rid,
                List.of(new OrderLine("EXAM", examItem, 1, null, null, null, null)), null).get(0);
        chargeService.settle(rid, "CASH", null);
        em.flush();
        var wl = medTech.risWorklist(null).getData();
        Long examId = wl.stream().filter(w -> order.getGroupNo().equals(w.get("group_no")))
                .map(w -> ((Number) w.get("id")).longValue()).findFirst().orElseThrow();
        return new ExamCase(pid, order.getId(), examId);
    }

    private Authentication userAuth(String username) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) values (?, 'x', ?, true) "
                + "on conflict (username) do nothing", username, username);
        return new UsernamePasswordAuthenticationToken(username, null, List.of());
    }

    @Test
    void arrivalStateMachine() {
        var c = examCase();
        assertEquals(0, medTech.arrive(c.examId()).getCode());
        assertEquals("ARRIVED", jdbc.queryForObject("select status from ris_exam where id=?", String.class, c.examId()));
        assertNotNull(jdbc.queryForObject("select arrived_at from ris_exam where id=?", Object.class, c.examId()));
        // 重复到检 9944；ARRIVED 可直接写报告
        assertEquals(9944, medTech.arrive(c.examId()).getCode());
        assertEquals(0, medTech.writeReport(c.examId(),
                new MedTechController.RisReportReq("窦性心律", "正常"), admin).getCode());
    }

    @Test
    void mutualRecognitionListsRecentVerified() {
        var c = examCase();
        medTech.writeReport(c.examId(), new MedTechController.RisReportReq("窦性心律", "正常心电图"), admin);
        medTech.verifyReport(c.examId(), userAuth("ris_verifier_v38"));
        em.flush();
        var recent = medTech.recentExams(c.patientId(), null).getData();
        assertEquals(1, recent.size(), "30 天窗口内应命中已审报告");
        assertEquals("正常心电图", recent.get(0).get("impression"));
        // 按名过滤：不同名不命中
        assertTrue(medTech.recentExams(c.patientId(), "根本不存在的检查").getData().isEmpty());
    }

    @Test
    void templateTypeFilter() {
        medTech.createTemplate(new MedTechController.EmrTemplateReq(null, "胸片模板v38", "两肺纹理清晰", "RIS"));
        em.flush();
        var ris = medTech.templates(null, "RIS").getData();
        assertTrue(ris.stream().anyMatch(t -> "胸片模板v38".equals(t.get("name"))));
        assertTrue(ris.stream().allMatch(t -> "RIS".equals(t.get("template_type"))), "type 过滤应只出 RIS");
        // 缺省全量向后兼容
        assertTrue(medTech.templates(null, null).getData().size() >= ris.size());
    }
}
