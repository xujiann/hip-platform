package cn.hip.server;

import cn.hip.hrp.web.MiscController;
import cn.hip.hrp.web.MiscController.SrReq;
import cn.hip.hrp.web.MiscController.WindowReq;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 三十六期：科研台账审核流与窗口维护 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class ResearchWindowFlowTest {

    @Autowired MiscController controller;
    @Autowired JdbcTemplate jdbc;

    @Test
    void researchDraftOnlyEditAndReview() {
        assertEquals(0, controller.addResearch(
                new SrReq("IP", "专利转化：智能输液监控", "王研", "转化合同", new BigDecimal("200000"))).getCode());
        Long id = jdbc.queryForObject("select max(id) from sr_item", Long.class);
        assertEquals(0, controller.reviewResearch(id, true, "同意").getCode());
        // 已审核不可编辑/删除/再审
        assertEquals(4763, controller.editResearch(id,
                new SrReq("IP", "改名", null, null, null)).getCode());
        assertEquals(4763, controller.deleteResearch(id).getCode());
        assertEquals(4764, controller.reviewResearch(id, false, "x").getCode());
    }

    @Test
    void windowUpsertAndToggle() {
        assertEquals(4760, controller.saveWindow(new WindowReq("T01", "测试窗", "X", null)).getCode());
        assertEquals(0, controller.saveWindow(new WindowReq("T01", "测试窗", "CHARGE", null)).getCode());
        assertEquals(0, controller.toggleWindow("T01").getCode());
        String status = jdbc.queryForObject("select status from svc_window where win_no = 'T01'", String.class);
        assertEquals("CLOSED", status);
        // 幂等 upsert 改名
        assertEquals(0, controller.saveWindow(new WindowReq("T01", "测试窗2", "CHARGE", "OPEN")).getCode());
        String name = jdbc.queryForObject("select name from svc_window where win_no = 'T01'", String.class);
        assertEquals("测试窗2", name);
    }
}
