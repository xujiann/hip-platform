package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.qualitycare.web.NursingPlusController;
import cn.hip.qualitycare.web.NursingPlusController.HandoverReq;
import cn.hip.qualitycare.web.NursingQualityController;
import cn.hip.qualitycare.web.NursingQualityController.CareLevelReq;
import cn.hip.qualitycare.web.NursingRecordController;
import cn.hip.qualitycare.web.NursingRecordController.RecordReq;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v42 车道2：护理记录单 / 日常巡视 / 护理级别留痕 / 交接班双签。
 *
 * <p><b>本类先还测试欠账再测新功能</b>：交接班（shift_handover）自 V31 上线以来 JUnit 零命中，
 * 只有一条 E2E 顺跑。§① 先把既有交班行为逐条钉死（4706 必填、不传日期吃当天、列表 join 形状），
 * 再在 §② 之后加新行为——这与 Phase113FinanceTest 静默失效是同一类风险，
 * 且 v43 动写路径校验时唯一的安全网就是这批用例。
 *
 * <p>另有两条是本版的**契约保护**断言，不是功能断言：
 * {@link #compatCareLevelEndpointKeepsOldContract()} 钉死既有 care-level 端点不因新增留痕
 * 而多出必填项（E2E e2e-phase912 直接 {@code PUT ?level=一级} 无 reason）；
 * {@link #legacyArchivedRowsAreNeverBackfilled()} 钉死 archived_at 绝不用 discharged_at 回填。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V42NursingRecordTest {

    @Autowired NursingRecordController nursingRecordController;
    @Autowired NursingQualityController nursingQualityController;
    @Autowired NursingPlusController nursingPlusController;
    @Autowired InpatientService inpatientService;
    @Autowired PatientService patientService;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private final Authentication admin = new UsernamePasswordAuthenticationToken("admin", null, List.of());

    /** 造账号并返回其 Authentication（签名/签收都要求「非本人不可代」，必须有真实 sys_user 行） */
    private Authentication userAuth(String username) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) "
                + "values (?, 'x', ?, true) on conflict (username) do nothing", username, username);
        return new UsernamePasswordAuthenticationToken(username, null, List.of());
    }

    private Long userId(String username) {
        return jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
    }

    private Long admit(String name) {
        Patient p = new Patient();
        p.setName(name + System.nanoTime());
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        Long id = inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎",
                new BigDecimal("5000"), "CASH", null).getId();
        entityManager.flush();
        return id;
    }

    private Long addRecord(Long admId, String kind, Authentication auth) {
        var r = nursingRecordController.add(
                new RecordReq(admId, kind, null, "神志清、T37.0℃", "协助翻身", "患者诉舒适", null), auth);
        assertEquals(0, r.getCode(), r.getMessage());
        return ((Number) r.getData().get("id")).longValue();
    }

    // ===== ① 交接班：先锁既有行为（此前零 JUnit）=====

    @Test
    void handoverBaselineSummaryRequiredAndDefaultsToToday() {
        // 当班情况必填（既有 4706，本版未动）
        assertEquals(4706, nursingPlusController.addHandover(
                new HandoverReq(1L, "DAY", "  ", "待办", null), admin).getCode());
        assertEquals(4706, nursingPlusController.addHandover(
                new HandoverReq(1L, "DAY", null, "待办", null), admin).getCode());

        // 不传 shiftDate 仍吃当天（E2E e2e-phase3234 正是这样调用的，契约不能变）
        assertEquals(0, nursingPlusController.addHandover(
                new HandoverReq(1L, "DAY", "当班平稳", "留观 2 床", null), admin).getCode());
        Long id = jdbc.queryForObject("select max(id) from shift_handover", Long.class);
        var row = jdbc.queryForMap("select * from shift_handover where id = ?", id);
        assertEquals(LocalDate.now(), ((java.sql.Date) row.get("shift_date")).toLocalDate());
        assertEquals("admin", row.get("author"), "交班人取登录名（既有 varchar 口径，本版未改）");
        assertEquals("当班平稳", row.get("summary"));
        assertNull(row.get("receiver_id"), "新交的班默认未签收");

        // 列表 join 形状（dept_name 来自 sys_dept）
        var list = nursingPlusController.handovers().getData();
        assertTrue(list.stream().anyMatch(h -> id.equals(((Number) h.get("id")).longValue())
                && h.get("dept_name") != null));
    }

    /** v42 口径修复：夜班跨零点补录必须能指定班次日期，否则永远记到次日 */
    @Test
    void handoverAcceptsExplicitShiftDateForNightShiftBackfill() {
        String yesterday = LocalDate.now().minusDays(1).toString();
        assertEquals(0, nursingPlusController.addHandover(
                new HandoverReq(1L, "NIGHT", "夜班平稳，晨 8:30 补录", null, yesterday), admin).getCode());
        Long id = jdbc.queryForObject("select max(id) from shift_handover", Long.class);
        assertEquals(LocalDate.now().minusDays(1), jdbc.queryForObject(
                "select shift_date from shift_handover where id = ?", java.sql.Date.class, id).toLocalDate(),
                "夜班补录须落在班次当日，不能吃 current_date 记到次日");

        // 非法日期不静默吞掉（否则倒填会悄悄变成当天）
        assertEquals(4811, nursingPlusController.addHandover(
                new HandoverReq(1L, "NIGHT", "格式错", null, "2026/09/01"), admin).getCode());
    }

    /** v42 交接班双签：接班人签收，且交班人不能自己签收 */
    @Test
    void handoverReceiveIsDoubleSignedAndSelfReceiveRejected() {
        Authentication giver = userAuth("nur42giver");
        Authentication taker = userAuth("nur42taker");
        assertEquals(0, nursingPlusController.addHandover(
                new HandoverReq(1L, "DAY", "白班交班", "翻身 q2h", null), giver).getCode());
        Long id = jdbc.queryForObject("select max(id) from shift_handover", Long.class);

        // 交班人自己签收 → 4812（双签的意义就在于两个人）
        assertEquals(4812, nursingPlusController.receiveHandover(id, giver).getCode());
        assertNull(jdbc.queryForObject(
                "select receiver_id from shift_handover where id = ?", Long.class, id));

        // 接班人签收 → 落 sys_user 外键 + 时间
        assertEquals(0, nursingPlusController.receiveHandover(id, taker).getCode());
        var row = jdbc.queryForMap("select receiver_id, received_at from shift_handover where id = ?", id);
        assertEquals(userId("nur42taker"), ((Number) row.get("receiver_id")).longValue());
        assertNotNull(row.get("received_at"));

        // 重复签收 / 不存在 → 4810
        assertEquals(4810, nursingPlusController.receiveHandover(id, giver).getCode());
        assertEquals(4810, nursingPlusController.receiveHandover(-1L, taker).getCode());

        // 登录名不在 sys_user（如集成账号）无法签收，宁可拒绝也不落 null 签收人
        assertEquals(0, nursingPlusController.addHandover(
                new HandoverReq(1L, "MID", "中班交班", null, null), giver).getCode());
        Long id2 = jdbc.queryForObject("select max(id) from shift_handover", Long.class);
        assertEquals(4813, nursingPlusController.receiveHandover(
                id2, new UsernamePasswordAuthenticationToken("ghost42", null, List.of())).getCode());
        assertNull(jdbc.queryForObject(
                "select receiver_id from shift_handover where id = ?", Long.class, id2));

        // 列表带出签收人姓名
        var listed = nursingPlusController.handovers().getData().stream()
                .filter(h -> id.equals(((Number) h.get("id")).longValue())).findFirst().orElseThrow();
        assertEquals("nur42taker", listed.get("receiver_name"));
    }

    // ===== ② 护理记录 CRUD =====

    @Test
    void createValidatesKindContentAndAdmission() {
        Long admId = admit("护理记录校验");

        // 记录类型非法
        assertEquals(4801, nursingRecordController.add(
                new RecordReq(admId, "PATROL", null, "观察", null, null, null), admin).getCode());
        assertEquals(4801, nursingRecordController.add(
                new RecordReq(admId, null, null, "观察", null, null, null), admin).getCode());
        // 观察与措施同时为空
        assertEquals(4802, nursingRecordController.add(
                new RecordReq(admId, "OBSERVE", null, "  ", null, "效果", null), admin).getCode());
        // 住院记录不存在
        assertEquals(4805, nursingRecordController.add(
                new RecordReq(-1L, "OBSERVE", null, "观察", null, null, null), admin).getCode());

        // 只填措施也可以（巡视多数只有措施没有观察）
        assertEquals(0, nursingRecordController.add(
                new RecordReq(admId, "ROUNDS", null, null, "晨间护理、口腔护理", null, null), admin).getCode());
    }

    @Test
    void dischargedAdmissionRejectsNewRecordButStillReadable() {
        Long admId = admit("出院后写入");
        Long rid = addRecord(admId, "OBSERVE", admin);
        inpatientService.discharge(admId, null, "CASH");
        entityManager.flush();

        assertEquals(4805, nursingRecordController.add(
                new RecordReq(admId, "OBSERVE", null, "出院后补写", null, null, null), admin).getCode(),
                "已出院不能再新增护理记录");
        // 但读侧照常（病案装订与打印需要）
        var listed = nursingRecordController.list(admId, null, null, null);
        assertEquals(0, listed.getCode());
        assertTrue(listed.getData().stream().anyMatch(r -> rid.equals(((Number) r.get("id")).longValue())));
        assertEquals(0, nursingRecordController.printNursingRecord(admId, null, null, null).getCode());
    }

    @Test
    void listFiltersByKindAndTimeWindow() {
        Long admId = admit("护理记录筛选");
        addRecord(admId, "OBSERVE", admin);
        addRecord(admId, "ROUNDS", admin);
        addRecord(admId, "ROUNDS", admin);

        assertEquals(3, nursingRecordController.list(admId, null, null, null).getData().size());
        assertEquals(2, nursingRecordController.list(admId, "ROUNDS", null, null).getData().size());
        assertEquals(1, nursingRecordController.list(admId, "OBSERVE", null, null).getData().size());
        assertEquals(0, nursingRecordController.list(admId, "MEASURE", null, null).getData().size());
        assertEquals(4801, nursingRecordController.list(admId, "PATROL", null, null).getCode());

        // 时间窗右端按「当日 24 点」闭合——填今天必须包含今天写的记录
        String today = LocalDate.now().toString();
        assertEquals(3, nursingRecordController.list(admId, null, today, today).getData().size());
        assertEquals(0, nursingRecordController.list(admId, null, null,
                LocalDate.now().minusDays(1).toString()).getData().size());
        assertEquals(4811, nursingRecordController.list(admId, null, "2026/09/01", null).getCode());
    }

    /** 倒填时间必须原样落库（夜班补录），不能被静默改成 now() */
    @Test
    void explicitRecordTimeIsHonoured() {
        Long admId = admit("倒填时间");
        var r = nursingRecordController.add(new RecordReq(admId, "ROUNDS",
                LocalDate.now().minusDays(1) + " 23:30:00", null, "夜间巡视，患者入睡", null, null), admin);
        assertEquals(0, r.getCode());
        Long id = ((Number) r.getData().get("id")).longValue();
        var t = jdbc.queryForObject("select record_time from nur_record where id = ?",
                java.sql.Timestamp.class, id).toLocalDateTime();
        assertEquals(LocalDate.now().minusDays(1), t.toLocalDate());
        assertEquals(23, t.getHour());

        assertEquals(4811, nursingRecordController.add(
                new RecordReq(admId, "ROUNDS", "昨天晚上", null, "巡视", null, null), admin).getCode());
    }

    // ===== ③ 签名与签名冻结 =====

    @Test
    void signIsSelfOnlyAndFreezesRecord() {
        Long admId = admit("护理签名");
        Authentication nurse = userAuth("nur42writer");
        Authentication other = userAuth("nur42other");
        Long id = addRecord(admId, "OBSERVE", nurse);

        // 非本人不可签（nurse_id 是 sys_user 外键才做得到这条校验）
        assertEquals(4804, nursingRecordController.sign(id, other).getCode());
        assertNull(jdbc.queryForObject("select signature from nur_record where id = ?", String.class, id));

        // 记录不存在
        assertEquals(4800, nursingRecordController.sign(-1L, nurse).getCode());
        assertEquals(4800, nursingRecordController.update(-1L,
                new RecordReq(admId, "OBSERVE", null, "改", null, null, null)).getCode());

        // 签名前可改
        assertEquals(0, nursingRecordController.update(id,
                new RecordReq(admId, "OBSERVE", null, "神志清、T38.2℃", "物理降温", "30 分钟后复测", null)).getCode());
        assertEquals("物理降温", jdbc.queryForObject(
                "select measure from nur_record where id = ?", String.class, id));

        // 本人签名 → 冻结
        var signed = nursingRecordController.sign(id, nurse);
        assertEquals(0, signed.getCode());
        assertNotNull(signed.getData().get("signature"));
        var row = jdbc.queryForMap("select signature, signed_at, nurse_id from nur_record where id = ?", id);
        assertNotNull(row.get("signature"));
        assertNotNull(row.get("signed_at"));
        assertEquals(userId("nur42writer"), ((Number) row.get("nurse_id")).longValue());

        // 已签名不可改（4803）、不可重签（4808）
        assertEquals(4803, nursingRecordController.update(id,
                new RecordReq(admId, "OBSERVE", null, "签名后偷改", null, null, null)).getCode());
        assertEquals("物理降温", jdbc.queryForObject(
                "select measure from nur_record where id = ?", String.class, id), "签名后内容一字不得变");
        assertEquals(4808, nursingRecordController.sign(id, nurse).getCode());
    }

    // ===== ④ 只读预检（不挂写路径）=====

    @Test
    void gateCheckIsReadOnlyAndDefaultsOff() {
        Long admId = admit("护理预检");
        var empty = nursingRecordController.gateCheck(admId).getData();
        assertEquals("off", empty.get("gate"), "新数据未沉淀，默认必须 off 而非 warn");
        assertEquals(false, empty.get("complete"));
        @SuppressWarnings("unchecked")
        var missing0 = (List<String>) empty.get("missing");
        assertTrue(missing0.contains("无任何护理记录"));
        assertTrue(missing0.contains("无日常巡视记录"));

        Authentication nurse = userAuth("nur42gate");
        Long id = addRecord(admId, "ROUNDS", nurse);
        @SuppressWarnings("unchecked")
        var missing1 = (List<String>) nursingRecordController.gateCheck(admId).getData().get("missing");
        assertTrue(missing1.stream().anyMatch(s -> s.contains("未签名")));

        nursingRecordController.sign(id, nurse);
        var after = nursingRecordController.gateCheck(admId).getData();
        assertEquals(true, after.get("complete"));
        assertEquals(1L, ((Number) after.get("rounds_count")).longValue());

        // 出院一路畅通：本版绝不把护理记录判定挂进 emr.gate.discharge / emr.gate.archive
        Long bare = admit("护理预检不挡出院");
        assertDoesNotThrow(() -> inpatientService.discharge(bare, null, "CASH"));
    }

    // ===== ⑤ 打印数据集 =====

    @Test
    void printDatasetCarriesHeaderAndChineseKindNames() {
        Long admId = admit("护理记录打印");
        addRecord(admId, "ROUNDS", admin);
        var d = nursingRecordController.printNursingRecord(admId, null, null, null).getData();
        assertNotNull(d.get("patient_name"));
        assertNotNull(d.get("admission_no"));
        assertNotNull(d.get("ward_name"));
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) d.get("rows");
        assertEquals(1, rows.size());
        assertEquals("日常巡视", rows.get(0).get("kind_name"), "打印分支不该再自己维护一份中英映射");
        assertEquals(4805, nursingRecordController.printNursingRecord(-1L, null, null, null).getCode());
    }

    // ===== ⑥ 护理级别留痕 =====

    /**
     * 既有 care-level 端点的**契约保护**：9801 码不变、reason 不必填、返回体仍是 R&lt;Void&gt;。
     * E2E e2e-phase912 直接 {@code PUT ?level=一级}，任何新增必填都会打断 CI。
     */
    @Test
    void compatCareLevelEndpointKeepsOldContract() {
        Long admId = admit("护理级别兼容");
        assertEquals(9801, nursingQualityController.setCareLevel(admId, "半级", null, admin).getCode(),
                "非法级别仍走既有 9801，不得改成 4806");
        assertEquals(0, nursingQualityController.setCareLevel(admId, "一级", null, admin).getCode(),
                "不传 reason 必须照常放行");
        assertEquals("一级", jdbc.queryForObject(
                "select care_level from inp_admission where id = ?", String.class, admId));
        // 追加的留痕：既有 update 语义未变，只是多了一条日志行
        var logs = jdbc.queryForList("select * from nur_care_level_log where admission_id = ?", admId);
        assertEquals(1, logs.size());
        assertEquals("一级", logs.get(0).get("level"));
        assertNull(logs.get(0).get("reason"), "兼容端点不强制原因");
        assertNotNull(logs.get(0).get("effective_from"));
    }

    @Test
    void changeCareLevelRequiresReasonAndLogsWho() {
        Long admId = admit("护理级别留痕");
        Authentication nurse = userAuth("nur42level");

        assertEquals(4806, nursingQualityController.changeCareLevel(
                admId, new CareLevelReq("零级", "原因"), nurse).getCode());
        assertEquals(4806, nursingQualityController.changeCareLevel(
                admId, new CareLevelReq(null, "原因"), nurse).getCode());
        assertEquals(4807, nursingQualityController.changeCareLevel(
                admId, new CareLevelReq("特级", "  "), nurse).getCode());
        assertEquals(4807, nursingQualityController.changeCareLevel(
                admId, new CareLevelReq("特级", null), nurse).getCode());
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from nur_care_level_log where admission_id = ?", Integer.class, admId),
                "被拒的调级不得留痕");
        assertEquals(4805, nursingQualityController.changeCareLevel(
                -1L, new CareLevelReq("特级", "原因"), nurse).getCode());

        assertEquals(0, nursingQualityController.changeCareLevel(
                admId, new CareLevelReq("特级", "病情加重，转特级护理"), nurse).getCode());
        assertEquals("特级", jdbc.queryForObject(
                "select care_level from inp_admission where id = ?", String.class, admId));
        var log = jdbc.queryForMap(
                "select * from nur_care_level_log where admission_id = ? order by id desc limit 1", admId);
        assertEquals("特级", log.get("level"));
        assertEquals("病情加重，转特级护理", log.get("reason"));
        assertEquals(userId("nur42level"), ((Number) log.get("changed_by")).longValue());

        // 再降一级 → 两条留痕（care_level 单列此前只留最后一次，历史全丢）
        assertEquals(0, nursingQualityController.changeCareLevel(
                admId, new CareLevelReq("二级", "病情稳定，降二级"), nurse).getCode());
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from nur_care_level_log where admission_id = ?", Integer.class, admId));
    }

    /** 天数分档汇总：口径近似必须显式标注，不得把近似值伪装成精确统计 */
    @Test
    void careLevelDaysReportsCaliberHonestly() {
        Long logged = admit("护理天数留痕");
        nursingQualityController.changeCareLevel(logged, new CareLevelReq("特级", "术后特级"), admin);
        // 留痕时间回拨 2 天：PostgreSQL 的 now() 是事务时间戳，同一事务内写入与统计完全同刻，
        // 不回拨则区段长度恒为 0（真实场景跨事务不会如此）
        jdbc.update("update nur_care_level_log set effective_from = now() - interval '2 days' "
                + "where admission_id = ?", logged);
        // 无留痕住院（模拟 v42 之前的历史行）：直接置 care_level，不产生任何留痕
        Long legacy = admit("护理天数近似");
        jdbc.update("update inp_admission set care_level = '二级', admit_at = now() - interval '3 days' "
                + "where id = ?", legacy);

        var d = nursingQualityController.careLevelDays(
                LocalDate.now().minusDays(7).toString(), LocalDate.now().toString()).getData();
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) d.get("items");
        var special = items.stream().filter(i -> "特级".equals(i.get("careLevel"))).findFirst().orElseThrow();
        assertTrue(((java.math.BigDecimal) special.get("days")).doubleValue() >= 1.9,
                "留痕区段应按 effective_from → 现在计天");
        assertTrue(items.stream().anyMatch(i -> "二级".equals(i.get("careLevel"))));
        assertTrue(((Number) d.get("loggedAdmissions")).longValue() >= 1);
        assertTrue(((Number) d.get("approxAdmissions")).longValue() >= 1);
        assertEquals(true, d.get("approximate"), "存在无留痕住院时必须置近似标记");
        assertNotNull(d.get("caliber"));
        assertTrue(String.valueOf(d.get("caliber")).contains("近似"),
                "无留痕住院按当前级别铺满的近似口径必须写在返回体里");
        // 排序按分级护理由重到轻
        var levels = items.stream().map(i -> String.valueOf(i.get("careLevel")))
                .filter(NursingQualityController.CARE_LEVELS::contains).toList();
        assertEquals(levels.stream().sorted(
                java.util.Comparator.comparingInt(NursingQualityController.CARE_LEVELS::indexOf)).toList(),
                levels);
    }

    // ===== ⑦ 归档时间与归档人 =====

    @Test
    void archiveStampsWhenAndWho() {
        Long admId = admit("归档留痕");
        inpatientService.discharge(admId, null, "CASH");
        entityManager.flush();
        jdbc.update("update sys_config set cfg_value = 'off' where cfg_key = 'emr.gate.archive'");

        userAuth("nur42archiver");
        var before = SecurityContextHolder.getContext().getAuthentication();
        try {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "nur42archiver", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            assertEquals(0, nursingQualityController.archive(admId).getCode());
        } finally {
            SecurityContextHolder.getContext().setAuthentication(before);
        }

        var row = jdbc.queryForMap("select archived, archived_at, archived_by from inp_admission where id = ?", admId);
        assertEquals(true, row.get("archived"), "既有 archived 布尔位语义不变");
        assertNotNull(row.get("archived_at"), "归档时间必须落库（车道3 终末质控要读）");
        assertEquals(userId("nur42archiver"), ((Number) row.get("archived_by")).longValue());
    }

    /**
     * 历史归档行**必然为 null**，严禁用 discharged_at 回填伪造。
     * 模拟一条 v42 之前留下的归档行（只置 archived 布尔位），断言两列仍为 null——
     * 任何"顺手补齐历史数据"的改动都会在这里现形。
     */
    @Test
    void legacyArchivedRowsAreNeverBackfilled() {
        Long admId = admit("历史归档行");
        inpatientService.discharge(admId, null, "CASH");
        entityManager.flush();
        jdbc.update("update inp_admission set archived = true where id = ?", admId);

        var row = jdbc.queryForMap("select archived, archived_at, archived_by, discharged_at "
                + "from inp_admission where id = ?", admId);
        assertEquals(true, row.get("archived"));
        assertNotNull(row.get("discharged_at"));
        assertNull(row.get("archived_at"), "出院≠归档，绝不能拿 discharged_at 当归档时间");
        assertNull(row.get("archived_by"));
    }

    // ===== ⑧ 白板入口 =====

    /** 白板露出护理文书条数——不在护士的主入口露出，新表就没人写 */
    @Test
    void boardExposesNursingRecordCount() {
        Long admId = admit("白板文书数");
        addRecord(admId, "ROUNDS", admin);
        addRecord(admId, "OBSERVE", admin);
        var row = nursingQualityController.board().getData().stream()
                .filter(b -> admId.equals(((Number) b.get("admission_id")).longValue()))
                .findFirst().orElseThrow();
        assertEquals(2L, ((Number) row.get("nursing_records")).longValue());
        // 既有列一个不少（E2E e2e-phase912 断言 care_level / admission_id）
        assertNotNull(row.get("care_level"));
        assertNotNull(row.get("pending_orders"));
    }
}
