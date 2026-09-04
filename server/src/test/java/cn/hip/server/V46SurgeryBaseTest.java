package cn.hip.server;

import cn.hip.inpatient.service.EmrIntegrityService;
import cn.hip.inpatient.service.InpatientService;
import cn.hip.medtech.service.SurgeryService;
import cn.hip.medtech.service.SurgeryService.CancelReq;
import cn.hip.medtech.service.SurgeryService.OpInfoReq;
import cn.hip.medtech.service.SurgeryService.TimepointReq;
import cn.hip.medtech.web.MedTechController;
import cn.hip.medtech.web.MedTechController.SurgeryDoneReq;
import cn.hip.medtech.web.MedTechController.SurgeryReq;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v46 车道K 回归：手术域地基（技术偏离表 1397★ 手术间为核心维度的排程视图 / 1426★ 取消手术四阶段 /
 * 1428★ 手术类别 / 1429★ ASA 分级 / 1438★1439★ 手术级别 / 1441★ 非计划再次手术）。
 *
 * <p><b>本类最要紧的一组断言不是"能打点"，是 §① 契约保护。</b>
 * {@code inp_surgery} 的三个既有端点（{@code POST /api/inpatient/surgeries}、
 * {@code GET /api/inpatient/surgeries}、{@code PUT /api/inpatient/surgeries/{id}/complete}）
 * 自 v17 起被前端手术页、{@code e2e-phase1316.py}、{@code AnesController}、
 * {@code NursingQualityController}（病案首页手术信息）、{@code DrgController}、
 * {@code MedRecordStatsController} 六处消费。本版给 {@code inp_surgery} 加了 12 列、
 * 加了 6 个端点，§① 就是那句"三个老端点一个字节没动"的证据：
 * <ul>
 *   <li>既有 11 列的物理定义（类型/长度/可空性）逐列不变；</li>
 *   <li>POST 不传新字段时新列<b>全部为 NULL</b>（{@code is_unplanned_reop} 除外，
 *       它的 default false 语义是"未标记"不是"已确认不是"）；</li>
 *   <li>GET 的返回体<b>键集合逐键不变</b>——多一个键就会打断按位置/按键取值的消费方；</li>
 *   <li>complete 的行为与 9946 原样。</li>
 * </ul>
 *
 * <p><b>§②：v35 出院/归档完整性 gate 必须仍按 {@code status='DONE' and op_note is not null} 工作。</b>
 * 这是本车道最容易踩塌的一处：{@code EmrIntegrityService:93-100} 用
 * {@code status <> 'CANCELLED'} 判"是不是手术病例"、用 {@code status='DONE' and op_note is not null}
 * 判"缺不缺手术记录"。本版新增了 CANCELLED 的<b>写路径</b>（此前只有读侧在过滤这个值），
 * 因此必须钉死两件事：<b>登记时间点不改 status</b>、<b>已 DONE 的手术不可取消</b>——
 * 否则一台已完成手术被事后取消，该住院的手术病例判定当场失效，出院 gate 被悄悄掏空。
 *
 * <p>其余各节：§③ 四时间点顺序（正序通过、任意颠倒 4902）、§④ 取值白名单 4901/4903–4906、
 * §⑤ 取消四阶段 4907 与<b>取消后记录仍在</b>、§⑥ 手术间视图按手术间分组。
 *
 * <p><b>约定</b>：本车道新端点一律返回 {@code R.fail(code, msg)} 而不抛 {@code HipBizException}
 * （与既有 {@code MedTechController} 手术端点同风格），因此错误路径不会把测试事务标记
 * rollback-only，可以与后续断言写在同一个用例里。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = {"ADMIN", "TECHNICIAN"})
class V46SurgeryBaseTest {

    @Autowired MedTechController medTech;
    @Autowired SurgeryService surgeryService;
    @Autowired InpatientService inpatientService;
    @Autowired EmrIntegrityService emrIntegrityService;
    @Autowired PatientService patientService;
    @Autowired JdbcTemplate jdbc;

    /** {@code MedTechController.requestSurgery} 会 {@code currentUserService.idOf(auth)}，auth 不能为 null */
    private final Authentication auth = new UsernamePasswordAuthenticationToken("user", null, List.of());

    // ================= 夹具 =================

    private Long admit(String name) {
        Patient p = new Patient();
        p.setName(name + System.nanoTime());
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        Long bedId = freeBed();
        return inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎", new BigDecimal("500"), "CASH", null).getId();
    }

    /**
     * 取一张空床；**池子空了就自建**，不靠运气。
     *
     * <p>本类 13 个用例各占一床，而 inp_bed 种子池只有 10 张。本类 @Transactional 逐条回滚，
     * 类内不累积；但与其它同样从池子取床的测试类并存时余量本就紧张——表现为
     * 「单跑全绿、全量跑偶尔红」的不稳定（同一份代码同一个库，两次运行结果不同）。
     * 不稳定测试比失败测试更糟：它会训练人忽略红灯。故夹具自给自足，不与别的测试类抢床。
     */
    private Long freeBed() {
        var ids = jdbc.queryForList("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        if (!ids.isEmpty()) return ids.get(0);
        Long wardId = jdbc.queryForObject("select ward_id from inp_bed limit 1", Long.class);
        return jdbc.queryForObject(
                "insert into inp_bed(ward_id, bed_no, status) values (?, ?, 'FREE') returning id",
                Long.class, wardId, "T" + (System.nanoTime() % 100000000L));
    }
    /** 建一台手术并返回 id（走既有 POST 端点，不直接 insert——契约保护要测的正是这条路） */
    private Long newSurgery(Long admId, String procedure, String scheduledAt) {
        var r = medTech.requestSurgery(new SurgeryReq(admId, procedure, "全身麻醉", scheduledAt, null), auth);
        assertEquals(0, r.getCode(), "既有手术申请端点应成功：" + r.getMessage());
        return jdbc.queryForObject(
                "select id from inp_surgery where admission_id = ? order by id desc limit 1", Long.class, admId);
    }

    private static String uniqRoom() {
        return "OR" + System.nanoTime() % 100000L;
    }

    private Map<String, Object> rowOf(Long id) {
        return jdbc.queryForMap("select * from inp_surgery where id = ?", id);
    }

    private String columnType(String column) {
        return jdbc.queryForObject("""
                select data_type from information_schema.columns
                where table_name = 'inp_surgery' and column_name = ?
                """, String.class, column);
    }

    private Integer columnLength(String column) {
        return jdbc.queryForObject("""
                select character_maximum_length from information_schema.columns
                where table_name = 'inp_surgery' and column_name = ?
                """, Integer.class, column);
    }

    private String nullable(String column) {
        return jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                where table_name = 'inp_surgery' and column_name = ?
                """, String.class, column);
    }

    // ================= ① 契约保护（最重要） =================

    /**
     * <b>既有 11 列的物理定义一列没动，新加的 12 列全部可空、全部无 CHECK。</b>
     *
     * <p>"全部可空"不是洁癖：历史手术行的手术间与四个时间点<b>必然为空</b>，
     * 任何一列加 NOT NULL 都会逼出一次回填——而回填就是伪造
     * （拿 {@code scheduled_at} 填 {@code start_at} 会让首台准点开台率恒等于 100%）。
     */
    @Test
    void legacyColumnsUnchangedAndNewColumnsAllNullable() {
        assertEquals("bigint", columnType("id"));
        assertEquals("bigint", columnType("admission_id"));
        assertEquals("NO", nullable("admission_id"));
        assertEquals("character varying", columnType("procedure_name"));
        assertEquals(255, columnLength("procedure_name"));
        assertEquals("NO", nullable("procedure_name"));
        assertEquals("character varying", columnType("anesthesia_type"));
        assertEquals(32, columnLength("anesthesia_type"));
        assertEquals("timestamp with time zone", columnType("scheduled_at"));
        assertEquals("character varying", columnType("status"));
        assertEquals(16, columnLength("status"), "status 仍是 V53 放宽后的 varchar(16)");
        assertEquals("NO", nullable("status"));
        assertEquals("character varying", columnType("op_note"));
        assertEquals(2000, columnLength("op_note"), "op_note 是出院完整性 gate 的判定对象，列宽不动");
        assertEquals("character varying", columnType("anes_note"));
        assertEquals(2000, columnLength("anes_note"));
        assertEquals("bigint", columnType("surgeon_id"));
        assertEquals("character varying", columnType("op_icd"));
        assertEquals(16, columnLength("op_icd"));

        for (String c : List.of("room_no", "in_room_at", "start_at", "end_at", "out_room_at",
                "surgery_level", "asa_grade", "incision_type", "surgery_kind",
                "cancel_stage", "cancel_reason", "is_unplanned_reop")) {
            assertEquals("YES", nullable(c), c + " 必须可空——历史手术行这些字段必然为空，不许回填伪造");
        }

        // 白名单只落在写侧，数据库不加 CHECK（试点库历史脏值会挡住 Flyway）。
        // 走 pg_constraint 的 contype='c' 而不是 information_schema：后者把 NOT NULL 也列成 CHECK。
        Integer checks = jdbc.queryForObject("""
                select count(*) from pg_constraint
                where conrelid = 'inp_surgery'::regclass and contype = 'c'
                """, Integer.class);
        assertEquals(0, checks, "inp_surgery 不得新增 CHECK 约束（取值白名单在写侧）");
    }

    /**
     * <b>既有 POST 不传新字段时，12 个新列一个都没被写。</b>
     *
     * <p>{@code is_unplanned_reop} 是唯一有默认值的列，落 false——它的语义是"<b>未标记</b>"，
     * 不是"已确认不是非计划再次手术"，统计层据此计数时必须标注分母。
     */
    @Test
    void legacyPostLeavesEveryNewColumnEmpty() {
        Long admId = admit("契约POST");
        var r = medTech.requestSurgery(new SurgeryReq(admId, "阑尾切除术", "全身麻醉", null, null), auth);
        assertEquals(0, r.getCode(), "既有 POST 返回 code=0（知情同意 gate 默认 warn，不拦截）");
        assertNotNull(r.getData(), "既有 POST 返回体仍是 Map（warn 时带 warning 键）");

        var row = rowOf(jdbc.queryForObject(
                "select id from inp_surgery where admission_id = ? order by id desc limit 1", Long.class, admId));
        assertEquals("REQUESTED", row.get("status"), "不传排台时间仍是 REQUESTED——状态机一字未改");
        for (String c : List.of("room_no", "in_room_at", "start_at", "end_at", "out_room_at",
                "surgery_level", "asa_grade", "incision_type", "surgery_kind",
                "cancel_stage", "cancel_reason")) {
            assertNull(row.get(c), c + " 必须为空：既有 POST 不碰任何新列");
        }
        assertEquals(Boolean.FALSE, row.get("is_unplanned_reop"), "默认 false = 未标记，不是已确认不是");
    }

    /**
     * <b>既有 GET 的返回体键集合逐键不变。</b>
     *
     * <p>这是本类最锋利的一条断言：多返回一个键就会打断按键取值的前端与 E2E，
     * 所以新字段一律走<b>新开的 {@code /{id}/detail} 与 {@code /room-board}</b> 两条读通道。
     */
    @Test
    void legacyGetKeySetIsByteForByteTheSame() {
        Long admId = admit("契约GET");
        newSurgery(admId, "契约GET术式", null);

        List<Map<String, Object>> rows = medTech.surgeries().getData();
        var mine = rows.stream()
                .filter(m -> "契约GET术式".equals(m.get("procedure_name")))
                .findFirst().orElseThrow(() -> new AssertionError("新建手术应出现在既有列表端点里"));

        assertEquals(Set.of("id", "admission_id", "procedure_name", "anesthesia_type", "scheduled_at",
                        "status", "op_icd", "op_note", "anes_note", "admission_no", "patient_name"),
                mine.keySet(),
                "既有列表端点的分量集合是 v17 起的冻结契约，新字段必须走 /detail 与 /room-board");
    }

    /** 既有 complete：首次成功、再次 9946，op_note 语义与返回体原样 */
    @Test
    void legacyCompleteKeepsNineNineFourSix() {
        Long admId = admit("契约complete");
        Long sid = newSurgery(admId, "契约complete术式", null);

        assertEquals(0, medTech.completeSurgery(sid, new SurgeryDoneReq("手术顺利", "麻醉平稳")).getCode());
        var row = rowOf(sid);
        assertEquals("DONE", row.get("status"));
        assertEquals("手术顺利", row.get("op_note"));

        assertEquals(9946, medTech.completeSurgery(sid, new SurgeryDoneReq("再来一次", "再来一次")).getCode(),
                "重复完成仍返 9946（本版没新造码去替换它）");
    }

    // ================= ② v35 出院完整性 gate 不受影响 =================

    /**
     * <b>gate 仍按 {@code status='DONE' and op_note is not null} 工作</b>——
     * 这一节是 {@code EmrIntegrityGateTest.surgeryCaseRequiresOpNotePreopConsent} 的加强版：
     * 原用例只测"有未完成手术 → 缺三项"，这里补上本版新增的两条风险路径。
     */
    @Test
    void dischargeGateStillKeyedOnDoneAndOpNote() {
        Long admId = admit("gate手术病例");
        jdbc.update("insert into inp_medical_record(admission_id, record_type, title, content, signature) "
                + "values (?,'ADMISSION','入院记录','内容',null)", admId);
        jdbc.update("insert into inp_medical_record(admission_id, record_type, title, content, signature) "
                + "values (?,'PROGRESS','病程','内容',null)", admId);
        jdbc.update("insert into inp_medical_record(admission_id, record_type, title, content, signature) "
                + "values (?,'DISCHARGE','出院小结','内容','SIG')", admId);
        assertTrue(emrIntegrityService.check(admId).isEmpty(), "无手术时应完整");

        Long sid = newSurgery(admId, "gate术式", null);
        assertTrue(emrIntegrityService.check(admId).contains("缺手术记录"),
                "未完成手术 → 缺手术记录（既有口径）");

        // (1) 打满四个时间点、填满全部术中信息，**都不足以**顶替手术记录：gate 只认 DONE + op_note
        for (String stage : List.of("IN_ROOM", "START", "END", "OUT_ROOM")) {
            assertEquals(0, surgeryService.registerTimepoint(sid, new TimepointReq(stage, null)).getCode());
        }
        assertEquals(0, surgeryService.updateOpInfo(sid, new OpInfoReq(
                uniqRoom(), "三级", "II", "Ⅰ类", "ELECTIVE", true)).getCode());
        assertEquals("REQUESTED", rowOf(sid).get("status"),
                "登记时间点与术中信息**绝不改 status**——新增 status 取值会让 gate 与前端标签同时失真");
        assertTrue(emrIntegrityService.check(admId).contains("缺手术记录"),
                "时间点与术中信息不是手术记录，gate 判据仍是 status='DONE' and op_note is not null");

        // (2) 补 op_note 完成 → 缺手术记录消失（其余两项仍缺，与既有口径一致）
        assertEquals(0, medTech.completeSurgery(sid, new SurgeryDoneReq("手术顺利", "麻醉平稳")).getCode());
        var miss = emrIntegrityService.check(admId);
        assertFalse(miss.contains("缺手术记录"), miss.toString());
        assertTrue(miss.contains("缺术前小结"), miss.toString());
        assertTrue(miss.contains("缺手术知情同意书"), miss.toString());

        // (3) **已 DONE 的手术不可取消**——否则 status 变 CANCELLED 后 gate 认为"根本没手术"，
        //     三项要求凭空消失，出院完整性被悄悄掏空
        assertEquals(4900, surgeryService.cancel(sid, new CancelReq("IN_OP", "术中改期")).getCode());
        assertEquals("DONE", rowOf(sid).get("status"));
        assertTrue(emrIntegrityService.check(admId).contains("缺术前小结"), "gate 判定不受取消尝试影响");
    }

    // ================= ③ 四个时间点顺序校验 =================

    @Test
    void timepointsInOrderPassAndOutOfOrderReturns4902() {
        Long admId = admit("时间点");
        Long sid = newSurgery(admId, "时间点术式", null);
        String d = BusinessDates.today().toString();

        assertEquals(0, surgeryService.registerTimepoint(sid, new TimepointReq("IN_ROOM", d + "T09:00:00+08:00")).getCode());
        assertEquals(0, surgeryService.registerTimepoint(sid, new TimepointReq("START", d + "T09:30:00+08:00")).getCode());
        assertEquals(0, surgeryService.registerTimepoint(sid, new TimepointReq("END", d + "T11:00:00+08:00")).getCode());
        var last = surgeryService.registerTimepoint(sid, new TimepointReq("OUT_ROOM", d + "T11:15:00+08:00"));
        assertEquals(0, last.getCode());
        assertEquals("OUT", last.getData().get("phase"), "四点齐全 → 派生阶段 OUT（派生不落库）");

        var row = rowOf(sid);
        for (String c : List.of("in_room_at", "start_at", "end_at", "out_room_at")) assertNotNull(row.get(c));
        assertEquals("REQUESTED", row.get("status"), "打点全程不改 status");

        // 任意一处颠倒都返 4902
        assertEquals(4902, surgeryService.registerTimepoint(sid, new TimepointReq("START", d + "T08:00:00+08:00")).getCode(),
                "开台早于入室");
        assertEquals(4902, surgeryService.registerTimepoint(sid, new TimepointReq("END", d + "T09:10:00+08:00")).getCode(),
                "结束早于开台");
        assertEquals(4902, surgeryService.registerTimepoint(sid, new TimepointReq("OUT_ROOM", d + "T10:00:00+08:00")).getCode(),
                "出室早于结束");
        assertEquals(4902, surgeryService.registerTimepoint(sid, new TimepointReq("IN_ROOM", d + "T10:00:00+08:00")).getCode(),
                "入室晚于开台");

        // 颠倒被拒后库里还是原来的值（失败即不落库）
        assertEquals(row.get("start_at"), rowOf(sid).get("start_at"));
    }

    /**
     * 只校验"已存在的时间点之间不得颠倒"，<b>不要求前置时间点已登记</b>——
     * 真实补录顺序常与发生顺序相反（先补出室、再回头补入室），要求前置存在会把合法补录挡在门外。
     */
    @Test
    void timepointsAllowBackfillInAnyOrderAsLongAsChronologyHolds() {
        Long admId = admit("补录");
        Long sid = newSurgery(admId, "补录术式", null);
        String d = BusinessDates.today().toString();

        assertEquals(0, surgeryService.registerTimepoint(sid, new TimepointReq("OUT_ROOM", d + "T11:15:00+08:00")).getCode(),
                "先补出室：不要求入室/开台/结束已在");
        assertEquals(0, surgeryService.registerTimepoint(sid, new TimepointReq("IN_ROOM", d + "T09:00:00+08:00")).getCode());
        assertEquals(4902, surgeryService.registerTimepoint(sid, new TimepointReq("START", d + "T12:00:00+08:00")).getCode(),
                "开台晚于已登记的出室 → 仍是颠倒");
    }

    @Test
    void timepointRejectsBadStageBadTimeAndMissingSurgery() {
        Long admId = admit("时间点非法");
        Long sid = newSurgery(admId, "时间点非法术式", null);

        assertEquals(4908, surgeryService.registerTimepoint(sid, new TimepointReq("KAITAI", null)).getCode());
        assertEquals(4908, surgeryService.registerTimepoint(sid, new TimepointReq(null, null)).getCode());
        assertEquals(4909, surgeryService.registerTimepoint(sid, new TimepointReq("START", "昨天下午")).getCode());
        assertEquals(4900, surgeryService.registerTimepoint(-1L, new TimepointReq("START", null)).getCode());

        // 不带时区的字面量按业务时区解释（"日期 空格 时间"也认）
        assertEquals(0, surgeryService.registerTimepoint(sid, new TimepointReq("IN_ROOM", "2026-09-04 09:00:00")).getCode());
        assertNotNull(rowOf(sid).get("in_room_at"));
    }

    // ================= ④ 术中信息取值白名单 =================

    @Test
    void opInfoWhitelistsRejectIllegalValues() {
        Long admId = admit("白名单");
        Long sid = newSurgery(admId, "白名单术式", null);

        assertEquals(4901, surgeryService.updateOpInfo(sid, new OpInfoReq("   ", null, null, null, null, null)).getCode());
        assertEquals(4901, surgeryService.updateOpInfo(sid, new OpInfoReq("这个手术间编号超过十六个字符了真的很长", null, null, null, null, null)).getCode());
        assertEquals(4903, surgeryService.updateOpInfo(sid, new OpInfoReq(null, "五级", null, null, null, null)).getCode());
        assertEquals(4904, surgeryService.updateOpInfo(sid, new OpInfoReq(null, null, "VII", null, null, null)).getCode());
        assertEquals(4905, surgeryService.updateOpInfo(sid, new OpInfoReq(null, null, null, "五类", null, null)).getCode());
        assertEquals(4906, surgeryService.updateOpInfo(sid, new OpInfoReq(null, null, null, null, "择期", null)).getCode(),
                "手术类别存英文码，中文「择期」不在白名单里");
        assertEquals(4900, surgeryService.updateOpInfo(-1L, new OpInfoReq(null, "三级", null, null, null, null)).getCode());

        // 一次校验失败，整条 update 不执行
        for (String c : List.of("room_no", "surgery_level", "asa_grade", "incision_type", "surgery_kind")) {
            assertNull(rowOf(sid).get(c), c + " 在校验失败后不得被写入");
        }
    }

    @Test
    void opInfoAcceptsWhitelistedValuesAndUpdatesPartially() {
        Long admId = admit("白名单正例");
        Long sid = newSurgery(admId, "白名单正例术式", null);
        String room = uniqRoom();

        assertEquals(0, surgeryService.updateOpInfo(sid,
                new OpInfoReq(room, "四级", "III", "Ⅱ类", "EMERGENCY", true)).getCode());
        var row = rowOf(sid);
        assertEquals(room, row.get("room_no"));
        assertEquals("四级", row.get("surgery_level"));
        assertEquals("III", row.get("asa_grade"));
        assertEquals("Ⅱ类", row.get("incision_type"));
        assertEquals("EMERGENCY", row.get("surgery_kind"));
        assertEquals(Boolean.TRUE, row.get("is_unplanned_reop"));

        // 部分更新：只传手术级别，其余保持原样（null 是"本次不改"，不是"清空"）
        assertEquals(0, surgeryService.updateOpInfo(sid,
                new OpInfoReq(null, "一级", null, null, null, null)).getCode());
        var after = rowOf(sid);
        assertEquals("一级", after.get("surgery_level"));
        assertEquals(room, after.get("room_no"), "未传的字段不得被清空");
        assertEquals("EMERGENCY", after.get("surgery_kind"));

        // 白名单字典是前端下拉的唯一来源，三条列表 + 三组码名对不上就会前后端漂移
        var dict = medTech.surgeryDict().getData();
        assertEquals(SurgeryService.SURGERY_LEVELS, dict.get("surgeryLevels"));
        assertEquals(SurgeryService.ASA_GRADES, dict.get("asaGrades"));
        assertEquals(SurgeryService.INCISION_TYPES, dict.get("incisionTypes"));
        assertEquals(3, ((List<?>) dict.get("surgeryKinds")).size());
        assertEquals(4, ((List<?>) dict.get("cancelStages")).size());
        assertEquals(4, ((List<?>) dict.get("timepointStages")).size());
    }

    // ================= ⑤ 取消手术四阶段（1426★） =================

    /**
     * 四个阶段各取消一台，<b>记录全部还在</b>——1426★ 要的就是"申请/排程/入室前/术中
     * 分别取消了几台"，删了行就永远统计不出来。
     */
    @Test
    void cancelFourStagesKeepsRowsForStatistics() {
        Long admId = admit("取消四阶段");
        for (String stage : List.of("APPLY", "SCHEDULE", "PRE_IN", "IN_OP")) {
            Long sid = newSurgery(admId, "取消术式" + stage, null);
            assertEquals(0, surgeryService.cancel(sid, new CancelReq(stage, stage + " 阶段取消原因")).getCode());
            var row = rowOf(sid);
            assertEquals("CANCELLED", row.get("status"));
            assertEquals(stage, row.get("cancel_stage"));
            assertEquals(stage + " 阶段取消原因", row.get("cancel_reason"));
        }
        assertEquals(4, jdbc.queryForObject(
                "select count(*) from inp_surgery where admission_id = ? and status = 'CANCELLED'",
                Integer.class, admId), "取消不删除记录：四条 CANCELLED 行必须都还在");

        // 按阶段计数（这正是 1426★ 统计的取数形状）
        var byStage = jdbc.queryForList("""
                select cancel_stage, count(*) as n from inp_surgery
                where admission_id = ? and status = 'CANCELLED' group by cancel_stage
                """, admId);
        assertEquals(4, byStage.size(), "四个阶段各一台，按阶段分组应得四组");
    }

    @Test
    void cancelRejectsBadStageAndMissingReason() {
        Long admId = admit("取消非法");
        Long sid = newSurgery(admId, "取消非法术式", null);

        assertEquals(4907, surgeryService.cancel(sid, new CancelReq("BEFORE_ANES", "阶段拼错了")).getCode());
        assertEquals(4907, surgeryService.cancel(sid, new CancelReq(null, "阶段没传")).getCode());
        assertEquals(4907, surgeryService.cancel(sid, new CancelReq("SCHEDULE", "  ")).getCode(), "取消原因必填");
        assertEquals(4907, surgeryService.cancel(sid, new CancelReq("SCHEDULE", null)).getCode());
        assertEquals(4907, surgeryService.cancel(sid, new CancelReq("SCHEDULE", "很".repeat(201))).getCode(), "原因超 200 字");
        assertEquals("REQUESTED", rowOf(sid).get("status"), "校验失败不得改状态");

        assertEquals(0, surgeryService.cancel(sid, new CancelReq("SCHEDULE", "患者术前发热")).getCode());
        assertEquals(4900, surgeryService.cancel(sid, new CancelReq("SCHEDULE", "再取消一次")).getCode(),
                "已取消的手术不能再取消");
        assertEquals(4900, surgeryService.registerTimepoint(sid, new TimepointReq("IN_ROOM", null)).getCode(),
                "已取消的手术不能再打点");
        assertEquals(4900, surgeryService.updateOpInfo(sid, new OpInfoReq("OR-9", null, null, null, null, null)).getCode(),
                "已取消的手术不能再维护术中信息");
    }

    // ================= ⑥ 手术间排程视图（1397★） =================

    /**
     * <b>以手术间为核心维度分桶</b>（参数原话「以手术间为核心维度进行患者信息展示」）：
     * 每个手术间一个桶，未分配手术间的台次进「未排手术间」桶而不是被静默丢弃。
     */
    @Test
    void roomBoardGroupsByOperatingRoom() {
        Long admId = admit("排程视图");
        String d = BusinessDates.today().toString();
        String roomA = uniqRoom();
        String roomB = uniqRoom();

        Long a1 = newSurgery(admId, "A间第一台", d + "T09:00:00+08:00");
        Long a2 = newSurgery(admId, "A间第二台", d + "T13:00:00+08:00");
        Long b1 = newSurgery(admId, "B间第一台", d + "T10:00:00+08:00");
        Long unassigned = newSurgery(admId, "未排间台次", d + "T15:00:00+08:00");

        assertEquals(0, surgeryService.updateOpInfo(a1, new OpInfoReq(roomA, "三级", "II", "Ⅰ类", "ELECTIVE", null)).getCode());
        assertEquals(0, surgeryService.updateOpInfo(a2, new OpInfoReq(roomA, null, null, null, "DAY", null)).getCode());
        assertEquals(0, surgeryService.updateOpInfo(b1, new OpInfoReq(roomB, null, null, null, "EMERGENCY", null)).getCode());
        assertEquals(0, surgeryService.registerTimepoint(a1, new TimepointReq("IN_ROOM", d + "T08:50:00+08:00")).getCode());
        assertEquals(0, surgeryService.registerTimepoint(a1, new TimepointReq("START", d + "T09:05:00+08:00")).getCode());
        assertEquals(0, surgeryService.registerTimepoint(a1, new TimepointReq("END", d + "T10:05:00+08:00")).getCode());

        var buckets = medTech.surgeryRoomBoard(d, null).getData();
        var bucketA = bucket(buckets, roomA);
        assertEquals(2, bucketA.get("count"), "A 间当日两台");
        @SuppressWarnings("unchecked")
        var listA = (List<Map<String, Object>>) bucketA.get("surgeries");
        assertEquals(List.of("A间第一台", "A间第二台"), listA.stream().map(m -> m.get("procedure_name")).toList(),
                "桶内按时间升序");
        assertEquals("CLOSED", listA.get(0).get("phase"), "已结束未出室 → 派生阶段 CLOSED");
        assertEquals(60L, listA.get(0).get("durationMin"), "开台到结束 60 分钟");
        assertNull(listA.get(1).get("durationMin"), "没打点的台次时长为 null——严禁拿排台时间凑");
        assertEquals("择期", listA.get(0).get("surgeryKindName"));
        assertNotNull(listA.get(0).get("patient_name"), "1397★ 要求带患者信息");
        assertNotNull(listA.get(0).get("bed_no"));

        assertEquals(1, bucket(buckets, roomB).get("count"));

        // 未分配手术间的台次进「未排手术间」桶
        var none = bucket(buckets, "");
        assertEquals("未排手术间", none.get("roomName"));
        @SuppressWarnings("unchecked")
        var listNone = (List<Map<String, Object>>) none.get("surgeries");
        assertTrue(listNone.stream().anyMatch(m -> unassigned.equals(((Number) m.get("id")).longValue())),
                "未分配手术间的台次不得被静默丢弃");

        // 按手术间过滤
        var only = medTech.surgeryRoomBoard(d, roomA).getData();
        assertEquals(1, only.size());
        assertEquals(roomA, only.get(0).get("roomNo"));

        // 取消的台次照样列出，并带取消阶段与原因（手术间面板要看得见"这个时段为什么空出来"）
        assertEquals(0, surgeryService.cancel(a2, new CancelReq("PRE_IN", "患者血压过高")).getCode());
        @SuppressWarnings("unchecked")
        var listA2 = (List<Map<String, Object>>) bucket(medTech.surgeryRoomBoard(d, roomA).getData(), roomA).get("surgeries");
        var cancelled = listA2.stream().filter(m -> a2.equals(((Number) m.get("id")).longValue())).findFirst().orElseThrow();
        assertEquals("CANCELLED", cancelled.get("status"));
        assertEquals("入室前", cancelled.get("cancelStageName"));
        assertEquals("患者血压过高", cancelled.get("cancel_reason"));

        assertEquals(4909, medTech.surgeryRoomBoard("2026年9月4日", null).getCode(), "日期格式非法");
    }

    /** 详情端点：新字段的读通道（既有列表端点分量不动，维护对话框从这里回填） */
    @Test
    void detailExposesNewColumns() {
        Long admId = admit("详情");
        Long sid = newSurgery(admId, "详情术式", null);
        String room = uniqRoom();
        assertEquals(0, surgeryService.updateOpInfo(sid, new OpInfoReq(room, "二级", "I", "Ⅲ类", "DAY", true)).getCode());

        R<Map<String, Object>> r = medTech.surgeryDetail(sid);
        assertEquals(0, r.getCode());
        var d = r.getData();
        assertEquals(room, d.get("room_no"));
        assertEquals("二级", d.get("surgery_level"));
        assertEquals("Ⅲ类", d.get("incision_type"));
        assertEquals("日间", d.get("surgeryKindName"));
        assertEquals(Boolean.TRUE, d.get("is_unplanned_reop"));
        assertEquals("WAITING", d.get("phase"));
        assertNotNull(d.get("patient_name"));
        assertEquals(4900, medTech.surgeryDetail(-1L).getCode());
    }

    private static Map<String, Object> bucket(List<Map<String, Object>> buckets, String roomNo) {
        return buckets.stream().filter(b -> roomNo.equals(b.get("roomNo"))).findFirst()
                .orElseThrow(() -> new AssertionError("排程视图缺手术间桶：" + (roomNo.isEmpty() ? "未排手术间" : roomNo)));
    }
}
