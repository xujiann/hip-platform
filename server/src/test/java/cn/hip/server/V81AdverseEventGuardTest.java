package cn.hip.server;

import cn.hip.qualitycare.web.NursingQualityController;
import cn.hip.qualitycare.web.NursingQualityController.AdverseEventReq;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
@WithMockUser(username = "admin", roles = {"ADMIN", "QUALITY"})
class V81AdverseEventGuardTest {
    @Autowired NursingQualityController controller;
    @Autowired JdbcTemplate jdbc;

    @Test
    void invalidTypeAndDateMustNotCreateAnEvent() {
        Long before = jdbc.queryForObject("select count(*) from qc_adverse_event", Long.class);
        String future = jdbc.queryForObject("select (current_date + 1)::text", String.class);
        String today = jdbc.queryForObject("select current_date::text", String.class);
        for (var req : new AdverseEventReq[]{
                new AdverseEventReq("任意文本", 1, today, null, "测试", true),
                new AdverseEventReq("跌倒", 1, future, null, "测试", true),
                new AdverseEventReq("跌倒", 1, "bad-date", null, "测试", true),
                new AdverseEventReq("跌倒", 1, null, null, "测试", true)}) {
            assertEquals(9804, controller.reportAdverse(req, null).getCode());
        }
        assertEquals(before, jdbc.queryForObject("select count(*) from qc_adverse_event", Long.class));
        assertEquals(0, controller.reportAdverse(new AdverseEventReq("跌倒", 1, today, null, "测试", true), null).getCode());
    }
}
