package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.medtech.service.SurgeryService;
import cn.hip.medtech.service.SurgeryService.CancelReq;
import cn.hip.medtech.service.SurgeryService.TimepointReq;
import cn.hip.medtech.web.AnesQcController;
import cn.hip.medtech.web.MedTechController;
import cn.hip.medtech.web.MedTechController.SurgeryReq;
import cn.hip.platform.core.common.R;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v81 审阅修补（审计 D6 / D7）：取消手术的取消时刻补录与取消阶段一致性。
 *
 * <ul>
 *   <li><b>D6</b> {@code PUT /inpatient/surgeries/{id}/cancel} 只能落「提交当刻」，取消时刻不能补录——
 *       事后登记的取消全落在登记日，1426★ 按取消日归集就错到登记那天。本版加可选 {@code at}
 *       （与时间点端点同格式、同解析口径，解析不出 4909）；早于手术申请（建单）或晚于当前 → <b>4911</b>。</li>
 *   <li><b>D7</b> 取消阶段由操作人手选、后端不核：从未入室的台次可记「术中取消」，已入室的可记「申请阶段取消」。
 *       本版校验阶段与已登记时间点一致，不一致 → <b>4910</b>（消息写明哪一项）：
 *       APPLY 须未排台、SCHEDULE / PRE_IN 须未入室、IN_OP 须已入室。</li>
 * </ul>
 * 请求体经 Jackson 反序列化构造（与线上同一条路径），顺带钉住 {@code at} 字段名。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = {"ADMIN", "TECHNICIAN"})
class V81SurgeryCancelGuardTest {

    @Autowired MedTechController medTech;
    @Autowired SurgeryService surgeryService;
    @Autowired AnesQcController anesQc;
    @Autowired InpatientService inpatientService;
    @Autowired PatientService patientService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper om;

    private final Authentication auth = new UsernamePasswordAuthenticationToken("user", null, List.of());
    private static final ZoneId BIZ = ZoneId.of("Asia/Shanghai");

    // ================= 夹具 =================

    private Long admit(String name) {
        Patient p = new Patient();
        p.setName(name + System.nanoTime());
        p.setSex("F");
        Long pid = patientService.register(p).getId();
        return inpatientService.admit(pid, 1L, freeBed(), null, "J18.9", "肺炎",
                new BigDecimal("500"), "CASH", null).getId();
    }

    private Long freeBed() {
        var ids = jdbc.queryForList("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        if (!ids.isEmpty()) return ids.get(0);
        Long wardId = jdbc.queryForObject("select ward_id from inp_bed limit 1", Long.class);
        return jdbc.queryForObject(
                "insert into inp_bed(ward_id, bed_no, status) values (?, ?, 'FREE') returning id",
                Long.class, wardId, "T" + (System.nanoTime() % 100000000L));
    }

    private Long newSurgery(Long admId, String procedure, String scheduledAt) {
        var r = medTech.requestSurgery(new SurgeryReq(admId, procedure, "全身麻醉", scheduledAt, null), auth);
        assertEquals(0, r.getCode(), r.getMessage());
        return jdbc.queryForObject(
                "select id from inp_surgery where admission_id = ? order by id desc limit 1", Long.class, admId);
    }

    private Map<String, Object> rowOf(Long id) {
        return jdbc.queryForMap("select * from inp_surgery where id = ?", id);
    }

    private static Instant instantOf(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp ts) return ts.toInstant();
        if (v instanceof OffsetDateTime odt) return odt.toInstant();
        if (v instanceof Instant i) return i;
        return fail("意料之外的时间类型：" + v.getClass());
    }

    /** 与线上同一条反序列化路径构造请求体 */
    private CancelReq req(String stage, String reason, String at) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stage", stage);
        body.put("reason", reason);
        if (at != null) body.put("at", at);
        return om.readValue(om.writeValueAsString(body), CancelReq.class);
    }

    private R<Void> cancel(Long id, String stage, String at) throws Exception {
        return surgeryService.cancel(id, req(stage, stage + " 阶段取消原因", at));
    }

    private void assertUntouched(Long id) {
        var row = rowOf(id);
        assertNotEquals("CANCELLED", row.get("status"), "被拒的取消不得改状态");
        assertNull(row.get("cancel_stage"));
        assertNull(row.get("cancelled_at"), "被拒的取消不得落取消时刻");
    }

    @Test
    void inconsistentLaterTimepointsMustRejectCancellationWithoutChangingRow() throws Exception {
        Long adm = admit("取消边界");
        for (String stage : List.of("APPLY", "PRE_IN", "IN_OP")) {
            Long sid = newSurgery(adm, "后续时间点" + stage, null);
            if (stage.equals("APPLY")) jdbc.update("update inp_surgery set in_room_at = now() - interval '1 hour' where id = ?", sid);
            if (stage.equals("PRE_IN")) jdbc.update("update inp_surgery set start_at = now() - interval '1 hour' where id = ?", sid);
            if (stage.equals("IN_OP")) jdbc.update("update inp_surgery set in_room_at = now() - interval '2 hours', out_room_at = now() - interval '1 hour' where id = ?", sid);
            assertEquals(4910, cancel(sid, stage, null).getCode());
            assertUntouched(sid);
        }
    }

    // ================= D7 取消阶段与已登记时间点一致 =================

    @Test
    void applyStageRequiresNoScheduleTime() throws Exception {
        Long adm = admit("阶段申请");
        Long scheduled = newSurgery(adm, "已排台", BizNow.plusHours(30));
        var r = cancel(scheduled, "APPLY", null);
        assertEquals(4910, r.getCode(), "已排台的台次不能登记为申请阶段取消");
        assertTrue(r.getMessage().contains("排台"), "消息写明哪一项：" + r.getMessage());
        assertUntouched(scheduled);

        Long applied = newSurgery(adm, "只申请", null);
        assertEquals(0, cancel(applied, "APPLY", null).getCode());
    }

    @Test
    void scheduleAndPreInStagesRequireNotInRoom() throws Exception {
        Long adm = admit("阶段入室前");
        for (String stage : List.of("SCHEDULE", "PRE_IN")) {
            Long sid = newSurgery(adm, "已入室" + stage, null);
            assertEquals(0, surgeryService.registerTimepoint(sid, new TimepointReq("IN_ROOM", null)).getCode());
            var r = cancel(sid, stage, null);
            assertEquals(4910, r.getCode(), "已入室的台次不能登记为 " + stage + " 阶段取消");
            assertTrue(r.getMessage().contains("入室"), r.getMessage());
            assertUntouched(sid);
        }
        assertEquals(0, cancel(newSurgery(adm, "未入室排程", BizNow.plusHours(30)), "SCHEDULE", null).getCode());
        assertEquals(0, cancel(newSurgery(adm, "未入室入室前", null), "PRE_IN", null).getCode());
    }

    @Test
    void inOpStageRequiresInRoom() throws Exception {
        Long adm = admit("阶段术中");
        Long notIn = newSurgery(adm, "未入室术中", BizNow.plusHours(30));
        var r = cancel(notIn, "IN_OP", null);
        assertEquals(4910, r.getCode(), "从未入室的台次不能登记为术中取消");
        assertTrue(r.getMessage().contains("入室"), r.getMessage());
        assertUntouched(notIn);

        Long in = newSurgery(adm, "已入室术中", null);
        assertEquals(0, surgeryService.registerTimepoint(in, new TimepointReq("IN_ROOM", null)).getCode());
        assertEquals(0, cancel(in, "IN_OP", null).getCode());
        assertEquals("IN_OP", rowOf(in).get("cancel_stage"));
    }

    /** 状态不允许（已完成）仍先报 4900，不被阶段校验抢先——既有 e2e 与用例按 4900 断言 */
    @Test
    void doneSurgeryStillReports4900First() throws Exception {
        Long adm = admit("阶段已完成");
        Long done = newSurgery(adm, "已完成", null);
        assertEquals(0, medTech.completeSurgery(done,
                new MedTechController.SurgeryDoneReq("手术记录", "麻醉记录")).getCode());
        assertEquals(4900, cancel(done, "IN_OP", null).getCode());
    }

    // ================= D6 取消时刻补录 =================

    /**
     * 申请（建单）三天前、两天前取消、今天才登记：补录 at = 两天前，cancelled_at 落两天前，
     * 1426★ 按取消日归集落在两天前那一天而不是今天。
     */
    @Test
    void cancelTimeCanBeBackfilledAndDrivesTheCancelDay() throws Exception {
        Long adm = admit("补录取消");
        Long sid = newSurgery(adm, "补录取消术式", null);
        jdbc.update("update inp_surgery set created_at = now() - interval '3 days' where id = ?", sid);
        String twoDaysAgo = jdbc.queryForObject(
                "select to_char((now() - interval '2 days') at time zone 'Asia/Shanghai', 'YYYY-MM-DD\"T\"HH24:MI:SS')",
                String.class);

        assertEquals(0, cancel(sid, "PRE_IN", twoDaysAgo).getCode());
        Instant stored = instantOf(rowOf(sid).get("cancelled_at"));
        assertEquals(java.time.LocalDateTime.parse(twoDaysAgo).atZone(BIZ).toInstant(), stored,
                "无时区的 at 按业务时区解释，与时间点端点同口径");

        String day = stored.atZone(BIZ).toLocalDate().toString();
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) ((List<Map<String, Object>>) anesQc
                .indicators(day, day, "1426").getData().get("indicators")).get(0).get("rows");
        assertTrue(rows.stream().anyMatch(r -> "PRE_IN".equals(r.get("cancel_stage"))),
                "1426★ 按补录的取消日归集：" + rows);
    }

    @Test
    void cancelTimeWithOffsetIsAccepted() throws Exception {
        Long adm = admit("补录带时区");
        Long sid = newSurgery(adm, "补录带时区术式", null);
        jdbc.update("update inp_surgery set created_at = now() - interval '1 day' where id = ?", sid);
        Instant t = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        assertEquals(0, cancel(sid, "APPLY", t.atZone(BIZ).toOffsetDateTime().toString()).getCode());
        assertEquals(t, instantOf(rowOf(sid).get("cancelled_at")));
    }

    /**
     * 4911：晚于当前、或术中取消早于入室时刻。<b>不以建单时刻为下界</b>（主控裁定）：
     * 手术申请本身也常是事后补录，时间点端点同样不以建单为下界——以建单为下界会让一切补录的取消都无法回到真实发生日。
     */
    @Test
    void cancelTimeInTheFutureOrBeforeInRoomIs4911() throws Exception {
        Long adm = admit("补录非法");
        Long inOp = newSurgery(adm, "补录非法术中", null);
        String inRoom = Instant.now().minus(1, ChronoUnit.DAYS).atZone(BIZ).toOffsetDateTime().toString();
        assertEquals(0, surgeryService.registerTimepoint(inOp, new TimepointReq("IN_ROOM", inRoom)).getCode());
        String beforeInRoom = Instant.now().minus(2, ChronoUnit.DAYS).atZone(BIZ).toOffsetDateTime().toString();
        var early = cancel(inOp, "IN_OP", beforeInRoom);
        assertEquals(4911, early.getCode(), "术中取消早于入室时刻非法");
        assertTrue(early.getMessage().contains("取消时刻非法"), early.getMessage());
        assertUntouched(inOp);

        Long sid = newSurgery(adm, "补录非法术式", null);
        jdbc.update("update inp_surgery set created_at = now() where id = ?", sid);
        String beforeCreate = Instant.now().minus(3, ChronoUnit.DAYS).atZone(BIZ).toOffsetDateTime().toString();
        assertEquals(0, cancel(newSurgery(adm, "补录早于建单", null), "APPLY", beforeCreate).getCode(),
                "早于建单的补录取消照收（申请本身也常是补录）");

        String future = Instant.now().plus(2, ChronoUnit.HOURS).atZone(BIZ).toOffsetDateTime().toString();
        var late = cancel(sid, "APPLY", future);
        assertEquals(4911, late.getCode(), "晚于当前的取消时刻非法");
        assertUntouched(sid);
    }

    @Test
    void unparsableCancelTimeIs4909() throws Exception {
        Long adm = admit("补录格式");
        Long sid = newSurgery(adm, "补录格式术式", null);
        assertEquals(4909, cancel(sid, "APPLY", "昨天下午").getCode(), "解析不出即报错，不静默吃成 now()");
        assertUntouched(sid);
    }

    /** 不传 at 仍取当前时刻（既有行为） */
    @Test
    void omittedCancelTimeIsNow() throws Exception {
        Long adm = admit("补录缺省");
        Long sid = newSurgery(adm, "补录缺省术式", null);
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertEquals(0, cancel(sid, "APPLY", "  ").getCode(), "空白 at 视同未传");
        Instant at = instantOf(rowOf(sid).get("cancelled_at"));
        assertFalse(at.isBefore(before));
        assertFalse(at.isAfter(Instant.now()));
    }

    /** 业务时区下「现在 + N 小时」的排台时刻串（带偏移，免受 JVM 时区影响） */
    private static final class BizNow {
        static String plusHours(int h) {
            return Instant.now().plus(h, ChronoUnit.HOURS).atZone(BIZ).toOffsetDateTime().toString();
        }
    }

    // Match the original WithMockUser authorities with a real current employee.
    @org.junit.jupiter.api.BeforeEach
    void currentSurgeryEmployeeFixture() {
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        String name=auth.getName();
        jdbc.update("insert into sys_user(username,password,real_name,enabled) values (?,'test-only',?,true) on conflict(username) do update set enabled=true",name,name);
        jdbc.update("delete from sys_user_role where user_id=(select id from sys_user where username=?)",name);
        for(var authority:auth.getAuthorities()) {
            String role=authority.getAuthority().substring("ROLE_".length());
            jdbc.update("insert into sys_user_role(user_id,role_id) select u.id,r.id from sys_user u cross join sys_role r where u.username=? and r.code=?",name,role);
        }
    }
}
