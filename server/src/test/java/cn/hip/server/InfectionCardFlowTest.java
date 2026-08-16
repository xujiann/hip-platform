package cn.hip.server;

import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.qualitycare.web.NursingPlusController;
import cn.hip.qualitycare.web.NursingPlusController.CardReq;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/** 三十三期：传染病报卡状态机（报卡→审核→上报，越级/类别校验） */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class InfectionCardFlowTest {

    @Autowired NursingPlusController controller;
    @Autowired PatientService patientService;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private final Authentication auth = new UsernamePasswordAuthenticationToken("tester", "x");

    private Long patient() {
        Patient p = new Patient();
        p.setName("报卡单测");
        p.setSex("U");
        Long id = patientService.register(p).getId();
        entityManager.flush(); // JPA→JDBC 可见
        return id;
    }

    @Test
    void reportReviewSubmitStateMachine() {
        Long pid = patient();
        assertEquals(4700, controller.reportCard(new CardReq(pid, "肺结核", "X", null), auth).getCode());
        assertEquals(0, controller.reportCard(new CardReq(pid, "肺结核", "B", null), auth).getCode());
        Long cardId = jdbc.queryForObject("select max(id) from idc_report_card", Long.class);
        // 未审核不能上报
        assertEquals(4703, controller.submit(cardId).getCode());
        assertEquals(0, controller.review(cardId, true, "属实").getCode());
        assertEquals(0, controller.submit(cardId).getCode());
        // 已上报不能再审
        assertEquals(4702, controller.review(cardId, false, "x").getCode());
        String status = jdbc.queryForObject("select status from idc_report_card where id = ?", String.class, cardId);
        assertEquals("SUBMITTED", status);
    }

    @Test
    void rejectedCardCannotSubmit() {
        Long pid = patient();
        controller.reportCard(new CardReq(pid, "流感", "C", null), auth);
        Long cardId = jdbc.queryForObject("select max(id) from idc_report_card", Long.class);
        assertEquals(0, controller.review(cardId, false, "信息不全").getCode());
        assertEquals(4703, controller.submit(cardId).getCode());
    }
}
