package cn.hip.medtech.service;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.config.HipProfiles;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v46 车道K：手术域地基（技术偏离表 1397★ 以手术间为核心维度的排程视图 / 1426★ 取消手术四阶段 /
 * 1428★ 手术类别 / 1429★ ASA 分级 / 1438★1439★ 手术级别 / 1441★ 非计划再次手术）。
 *
 * <p><b>本类存在的理由是"先补地基，统计才有真数据"。</b> {@code inp_surgery} 自 V17:45-56 建表
 * 只有 11 列（+V47:63 的 op_icd）：无手术间、无入室/开台/结束/出室四个时间点、无手术级别、
 * 无 ASA 分级、无切口等级、无手术类别、无取消阶段。而偏离表 1424–1450 那 27 条麻醉与手术
 * 质控指标全部要读这些列——不补地基，指标就是拿空表算出来的漂亮数字。
 *
 * <h3>一、契约保护：既有三个端点一个字节没动</h3>
 * {@code POST /api/inpatient/surgeries}、{@code GET /api/inpatient/surgeries}、
 * {@code PUT /api/inpatient/surgeries/{id}/complete} 三条自 v17 起的老端点<b>全部留在
 * {@code MedTechController} 原处，请求体、返回体、SQL 一行未改</b>；本类只提供
 * <b>新开的</b> 时间点登记 / 术中信息维护 / 取消手术 / 手术间排程视图四组能力。
 *
 * <p>最要紧的一条是 <b>status 与 op_note 的语义不可动</b>：v35 出院与归档完整性 gate
 * （{@code EmrIntegrityService:93-100}）判"手术病例是否缺手术记录"，用的正是
 * <pre>select count(*) from inp_surgery where admission_id = ? and status = 'DONE' and op_note is not null</pre>
 * 与 {@code status <> 'CANCELLED'} 两条 SQL。由此推出本类的两条硬规矩：
 * <ol>
 *   <li><b>登记时间点绝不顺手改 status。</b> 入室了也不写 status='IN_OP'——
 *       新增 status 取值会让上面两条 SQL 与前端 {@code REQUESTED/SCHEDULED/DONE} 标签映射
 *       同时失真。"这台手术进行到哪一步"由四个时间点<b>派生</b>（见 {@link #phaseOf}），不落库。</li>
 *   <li><b>已 DONE 的手术不可取消</b>（返 4900）。否则一台已完成手术被事后取消，
 *       会让该住院的手术病例判定当场失效——本来要求"手术记录+术前小结+手术知情同意"三项，
 *       取消后一项都不要了，出院 gate 被悄悄掏空。</li>
 * </ol>
 *
 * <h3>二、取值白名单（车道 M 的统计按此分组）</h3>
 * <table>
 *   <caption>写侧白名单，非法各返独立错误码</caption>
 *   <tr><th>列</th><th>取值</th><th>非法码</th></tr>
 *   <tr><td>{@code room_no}</td><td>非空、≤16 字符的自由文本</td><td>4901</td></tr>
 *   <tr><td>{@code surgery_level}</td><td>一级 / 二级 / 三级 / 四级</td><td>4903</td></tr>
 *   <tr><td>{@code asa_grade}</td><td>I / II / III / IV / V / VI（ASCII 大写）</td><td>4904</td></tr>
 *   <tr><td>{@code incision_type}</td><td>Ⅰ类 / Ⅱ类 / Ⅲ类 / Ⅳ类（全角罗马数字）</td><td>4905</td></tr>
 *   <tr><td>{@code surgery_kind}</td><td>ELECTIVE 择期 / EMERGENCY 急诊 / DAY 日间</td><td>4906</td></tr>
 *   <tr><td>{@code cancel_stage}</td><td>APPLY 申请 / SCHEDULE 排程 / PRE_IN 入室前 / IN_OP 术中</td><td>4907</td></tr>
 * </table>
 * 白名单<b>只落在写侧，数据库不加 CHECK</b>（见 V140 迁移注释纪律二）：试点库的实施期历史
 * 脏值会挡住 Flyway，迁移失败的代价远高于脏数据。读侧统计遇白名单外的值应归入"其他"而非报错。
 *
 * <h3>三、严禁回填伪造</h3>
 * 新列对历史手术行永远是 NULL——那是事实，当时根本没采集。<b>严禁拿 {@code scheduled_at}
 * 去填 {@code start_at}</b>：前者是"计划几点开"，后者是"实际几点开"，混为一谈会让 1424★
 * 首台准点开台率恒等于 100%、1425★ 接台时长恒等于 0。统计层必须显式排除 NULL 并标注分母，
 * 与 v41 床位效率趋势、v42 {@code archived_at} 同口径：<b>宁可少算，不可假算。</b>
 *
 * <h3>四、错误码 4900–4909</h3>
 * 4900 手术不存在或状态不允许（新端点专用；既有 complete 端点的"手术不存在或已完成"
 * <b>仍返 9946 原样不动</b>——那是被 E2E 与前端消费的老契约）/ 4901 手术间编号非法 /
 * 4902 手术时间点先后颠倒 / 4903 手术级别非法 / 4904 ASA 分级非法 / 4905 切口等级非法 /
 * 4906 手术类别非法 / 4907 取消阶段非法或取消原因缺失 / 4908 时间点阶段非法 /
 * 4909 时间点时间格式非法。
 */
@Service
@RequiredArgsConstructor
public class SurgeryService {

    private final JdbcTemplate jdbc;

    // ===================== 白名单（唯一真源，前端经 /dict 端点取，杜绝两头漂移） =====================

    /** 手术分级（《医疗机构手术分级管理办法》四级），1438★/1439★ 的分组键 */
    public static final List<String> SURGERY_LEVELS = List.of("一级", "二级", "三级", "四级");
    /** ASA 分级 I–VI。**ASCII 大写罗马数字**：它是统计分组键与 CSV 导出列，全半角混用会对不上账 */
    public static final List<String> ASA_GRADES = List.of("I", "II", "III", "IV", "V", "VI");
    /** 切口等级：Ⅰ类清洁 / Ⅱ类清洁-污染 / Ⅲ类污染 / Ⅳ类感染（国标写法用全角罗马数字） */
    public static final List<String> INCISION_TYPES = List.of("Ⅰ类", "Ⅱ类", "Ⅲ类", "Ⅳ类");
    /** 手术类别（1428★）。**存英文码不存中文**：它是 1424★ 首台准点率的过滤条件，要进 SQL 分支 */
    public static final Map<String, String> SURGERY_KINDS =
            ordered("ELECTIVE", "择期", "EMERGENCY", "急诊", "DAY", "日间");
    /** 取消阶段四档（1426★ 参数原话：申请、排程、入室前、术中） */
    public static final Map<String, String> CANCEL_STAGES =
            ordered("APPLY", "申请阶段", "SCHEDULE", "排程阶段", "PRE_IN", "入室前", "IN_OP", "术中");
    /** 时间点四档：入室 → 开台 → 结束 → 出室 */
    public static final Map<String, String> TIMEPOINT_STAGES =
            ordered("IN_ROOM", "入室", "START", "开台", "END", "结束", "OUT_ROOM", "出室");

    /** 有序且不可变的码-名表（{@code Map.of} 不保序，而下拉顺序本身就是业务口径的一部分） */
    private static Map<String, String> ordered(String... codeThenName) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < codeThenName.length; i += 2) m.put(codeThenName[i], codeThenName[i + 1]);
        return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * 阶段 → 列名。<b>列名只能从这张常量表里取</b>，绝不允许把入参拼进 SQL
     * （入参先过 {@link #TIMEPOINT_STAGES} 白名单，取不到就是 4908，根本走不到拼 SQL 那一步）。
     */
    private static final Map<String, String> TP_COLUMN = Map.of(
            "IN_ROOM", "in_room_at", "START", "start_at", "END", "end_at", "OUT_ROOM", "out_room_at");

    /** 四个时间点的**正序**，顺序校验按这个数组逐项比对 */
    private static final List<String> TP_ORDER = List.of("in_room_at", "start_at", "end_at", "out_room_at");

    // ===================== 请求体 =====================

    /**
     * @param stage 四档之一（IN_ROOM/START/END/OUT_ROOM），非法返 4908
     * @param at    ISO-8601 时间；<b>为空即取当前时刻</b>（手术间里那四个"打点"按钮就是这么用的），
     *              传值用于事后补录与更正
     */
    public record TimepointReq(String stage, String at) {}

    /** 术中信息维护：<b>只更新传了值的字段</b>，null 表示"本次不改"（不是"清空"） */
    public record OpInfoReq(String roomNo, String surgeryLevel, String asaGrade,
                            String incisionType, String surgeryKind, Boolean unplannedReop) {}

    /** 取消手术（1426★）：阶段与原因<b>都必填</b>，缺一返 4907 */
    public record CancelReq(String stage, String reason) {}

    // ===================== ① 四个时间点登记 =====================

    /**
     * 登记入室/开台/结束/出室之一。
     *
     * <p><b>只校验"已存在的时间点之间不得颠倒"，不要求前置时间点已登记。</b>
     * 理由是真实补录顺序未必与发生顺序一致（护士常常先补出室、再回头补入室），
     * 要求前置存在会把合法的补录挡在门外；而"入室 10:00 之后开台 09:00"这种**真正的颠倒**
     * 一定会被逐对比较抓住（4902）。
     *
     * <p><b>不改 status</b>——见类注释规矩 1。
     */
    @Transactional
    public R<Map<String, Object>> registerTimepoint(Long id, TimepointReq req) {
        String stage = req == null || req.stage() == null ? null : req.stage().trim().toUpperCase();
        if (stage == null || !TP_COLUMN.containsKey(stage)) {
            return R.fail(4908, "手术时间点阶段非法，只能是 IN_ROOM/START/END/OUT_ROOM");
        }
        Instant at;
        if (req.at() == null || req.at().isBlank()) {
            at = Instant.now();
        } else {
            at = parseInstant(req.at());
            if (at == null) return R.fail(4909, "手术时间点时间格式非法，请用 ISO-8601（如 2026-09-04T09:30:00+08:00）");
        }

        var rows = jdbc.queryForList("""
                select status, in_room_at, start_at, end_at, out_room_at
                from inp_surgery where id = ?
                """, id);
        if (rows.isEmpty()) return R.fail(4900, "手术记录不存在");
        var row = rows.get(0);
        if ("CANCELLED".equals(row.get("status"))) return R.fail(4900, "手术已取消，不能登记时间点");

        // 把本次登记合并进四个时间点，再整体做一次单调不减校验
        String column = TP_COLUMN.get(stage);
        Map<String, Instant> merged = new LinkedHashMap<>();
        for (String c : TP_ORDER) merged.put(c, c.equals(column) ? at : toInstant(row.get(c)));
        for (int i = 0; i < TP_ORDER.size(); i++) {
            Instant earlier = merged.get(TP_ORDER.get(i));
            if (earlier == null) continue;
            for (int j = i + 1; j < TP_ORDER.size(); j++) {
                Instant later = merged.get(TP_ORDER.get(j));
                if (later != null && later.isBefore(earlier)) {
                    return R.fail(4902, "手术时间点先后颠倒：" + labelOfColumn(TP_ORDER.get(j))
                            + "不能早于" + labelOfColumn(TP_ORDER.get(i)));
                }
            }
        }

        // column 取自 TP_COLUMN 常量表，非入参拼接
        jdbc.update("update inp_surgery set " + column + " = ? where id = ?", Timestamp.from(at), id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stage", stage);
        data.put("stageName", TIMEPOINT_STAGES.get(stage));
        data.put("at", at.toString());
        data.put("phase", phaseOf(merged));
        return R.ok(data);
    }

    // ===================== ② 术中信息维护 =====================

    /**
     * 维护手术间 / 手术级别 / ASA / 切口等级 / 手术类别 / 非计划再次手术标记。
     *
     * <p><b>部分更新</b>：只有传了值的字段才进 set 子句，其余保持原样。
     * 因此本端点<b>不提供"清空"语义</b>——把手术间改成空字符串返 4901 而不是清空，
     * 排程视图靠 room_no 分桶，误清空等于让一台手术从手术间面板上凭空消失。
     */
    @Transactional
    public R<Void> updateOpInfo(Long id, OpInfoReq req) {
        if (req == null) return R.ok();
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        if (req.roomNo() != null) {
            String room = req.roomNo().trim();
            if (room.isEmpty() || room.length() > 16) return R.fail(4901, "手术间编号必填且不超过 16 个字符");
            sets.add("room_no = ?");
            args.add(room);
        }
        if (req.surgeryLevel() != null) {
            String v = req.surgeryLevel().trim();
            if (!SURGERY_LEVELS.contains(v)) return R.fail(4903, "手术级别只能是 " + String.join("/", SURGERY_LEVELS));
            sets.add("surgery_level = ?");
            args.add(v);
        }
        if (req.asaGrade() != null) {
            String v = req.asaGrade().trim().toUpperCase();
            if (!ASA_GRADES.contains(v)) return R.fail(4904, "ASA 分级只能是 " + String.join("/", ASA_GRADES));
            sets.add("asa_grade = ?");
            args.add(v);
        }
        if (req.incisionType() != null) {
            String v = req.incisionType().trim();
            if (!INCISION_TYPES.contains(v)) return R.fail(4905, "切口等级只能是 " + String.join("/", INCISION_TYPES));
            sets.add("incision_type = ?");
            args.add(v);
        }
        if (req.surgeryKind() != null) {
            String v = req.surgeryKind().trim().toUpperCase();
            if (!SURGERY_KINDS.containsKey(v)) {
                return R.fail(4906, "手术类别只能是 ELECTIVE 择期 / EMERGENCY 急诊 / DAY 日间");
            }
            sets.add("surgery_kind = ?");
            args.add(v);
        }
        if (req.unplannedReop() != null) {
            sets.add("is_unplanned_reop = ?");
            args.add(req.unplannedReop());
        }
        if (sets.isEmpty()) return R.ok();

        args.add(id);
        // set 子句由上面固定的列名字面量拼成，值全部走占位符
        int n = jdbc.update("update inp_surgery set " + String.join(", ", sets)
                + " where id = ? and status <> 'CANCELLED'", args.toArray());
        return n == 0 ? R.fail(4900, "手术记录不存在或已取消") : R.ok();
    }

    // ===================== ③ 取消手术（1426★ 四阶段） =====================

    /**
     * 按四阶段之一取消手术。
     *
     * <p><b>取消不删除记录</b>——1426★ 要的就是"申请/排程/入室前/术中分别取消了几台"，
     * 删了行就永远统计不出来。落 {@code status='CANCELLED'} + {@code cancel_stage} + {@code cancel_reason}。
     *
     * <p><b>已 DONE 的手术不可取消</b>（条件更新，n=0 返 4900）：见类注释规矩 2，
     * 事后取消一台已完成手术会把该住院的出院完整性 gate 悄悄掏空。
     */
    @Transactional
    public R<Void> cancel(Long id, CancelReq req) {
        String stage = req == null || req.stage() == null ? null : req.stage().trim().toUpperCase();
        String reason = req == null || req.reason() == null ? "" : req.reason().trim();
        if (stage == null || !CANCEL_STAGES.containsKey(stage)) {
            return R.fail(4907, "取消阶段只能是 APPLY 申请 / SCHEDULE 排程 / PRE_IN 入室前 / IN_OP 术中");
        }
        if (reason.isEmpty()) return R.fail(4907, "取消原因必填");
        if (reason.length() > 200) return R.fail(4907, "取消原因不超过 200 个字符");

        int n = jdbc.update("""
                update inp_surgery set status = 'CANCELLED', cancel_stage = ?, cancel_reason = ?
                where id = ? and status in ('REQUESTED', 'SCHEDULED')
                """, stage, reason, id);
        return n == 0 ? R.fail(4900, "手术记录不存在，或已完成/已取消（已完成的手术不可取消）") : R.ok();
    }

    // ===================== ④ 手术间排程视图（1397★） =====================

    /**
     * 当日手术按<b>手术间</b>分桶（1397★ 参数原话「以手术间为核心维度进行患者信息展示」）。
     *
     * <p>日期口径 {@code coalesce(in_room_at, scheduled_at)}：已入室的按实际入室日归属，
     * 未入室的按排台日归属。两者都为空的台次（只申请、未排台）进「未排期」桶，
     * <b>不静默丢弃</b>——手术室护士长要看到的正是这些还没落位的台次。
     *
     * <p>取消的台次<b>照样列出</b>并带取消阶段与原因：手术间面板要看得见"这个时段空出来了、为什么"。
     */
    public R<List<Map<String, Object>>> roomBoard(String date, String roomNo) {
        // 不传日期即业务"今天"（禁止裸 LocalDate.now()，见 BusinessDates 类注释）；
        // 手术间面板永远是一个日窗口，不做"全表列出"——那不是排程视图，是导出。
        String day = date == null || date.isBlank() ? BusinessDates.today().toString() : date.trim();
        try {
            java.time.LocalDate.parse(day);
        } catch (RuntimeException e) {
            return R.fail(4909, "日期格式非法，请用 yyyy-MM-dd");
        }
        StringBuilder sql = new StringBuilder("""
                select s.id, s.admission_id, s.room_no, s.procedure_name, s.anesthesia_type, s.op_icd,
                       s.scheduled_at, s.in_room_at, s.start_at, s.end_at, s.out_room_at,
                       s.status, s.surgery_level, s.asa_grade, s.incision_type, s.surgery_kind,
                       s.is_unplanned_reop, s.cancel_stage, s.cancel_reason,
                       a.admission_no, b.bed_no, p.name as patient_name, p.sex, p.birth_date,
                       u.real_name as surgeon_name
                from inp_surgery s
                join inp_admission a on a.id = s.admission_id
                join inp_bed b on b.id = a.bed_id
                join empi_patient p on p.id = a.patient_id
                left join sys_user u on u.id = s.surgeon_id
                where 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        sql.append("""
                  and ((coalesce(s.in_room_at, s.scheduled_at) >= cast(? as date)
                        and coalesce(s.in_room_at, s.scheduled_at) < cast(? as date) + interval '1 day')
                   or (s.in_room_at is null and s.scheduled_at is null and s.created_at >= cast(? as date)
                        and s.created_at < cast(? as date) + interval '1 day'))
                """);
        args.add(day);
        args.add(day);
        args.add(day);
        args.add(day);
        if (roomNo != null && !roomNo.isBlank()) {
            sql.append(" and s.room_no = ? ");
            args.add(roomNo.trim());
        }
        sql.append(" order by s.room_no nulls last, coalesce(s.in_room_at, s.scheduled_at, s.created_at), s.id ");

        var rows = jdbc.queryForList(sql.toString(), args.toArray());

        Map<String, List<Map<String, Object>>> byRoom = new LinkedHashMap<>();
        for (var r : rows) {
            Map<String, Object> item = new LinkedHashMap<>(r);
            item.put("surgeryKindName", SURGERY_KINDS.get(String.valueOf(r.get("surgery_kind"))));
            item.put("cancelStageName", CANCEL_STAGES.get(String.valueOf(r.get("cancel_stage"))));
            Map<String, Instant> tp = new LinkedHashMap<>();
            for (String c : TP_ORDER) tp.put(c, toInstant(r.get(c)));
            item.put("phase", phaseOf(tp));
            // 台次时长（分钟）：只在开台与结束都有值时算，缺一律 null——严禁用排台时间凑（V140 纪律二）
            Instant st = tp.get("start_at");
            Instant en = tp.get("end_at");
            item.put("durationMin", st == null || en == null ? null : java.time.Duration.between(st, en).toMinutes());
            String room = r.get("room_no") == null ? "" : String.valueOf(r.get("room_no"));
            byRoom.computeIfAbsent(room, k -> new ArrayList<>()).add(item);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : byRoom.entrySet()) {
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("roomNo", e.getKey());
            bucket.put("roomName", e.getKey().isEmpty() ? "未排手术间" : e.getKey());
            bucket.put("count", e.getValue().size());
            bucket.put("surgeries", e.getValue());
            out.add(bucket);
        }
        return R.ok(out);
    }

    /**
     * 单台手术全字段详情（只读）。
     *
     * <p><b>刻意新开一条读通道，而不是给既有 {@code GET /api/inpatient/surgeries} 加列</b>——
     * 那条列表端点的分量集合是 v17 起的冻结契约（前端列表、e2e-phase1316 都在按它的键取值），
     * 加列就是改契约。术中信息维护对话框需要回填当前值，就从这里取。
     */
    public R<Map<String, Object>> detail(Long id) {
        var rows = jdbc.queryForList("""
                select s.id, s.admission_id, s.procedure_name, s.anesthesia_type, s.op_icd,
                       s.scheduled_at, s.status, s.op_note, s.anes_note,
                       s.room_no, s.in_room_at, s.start_at, s.end_at, s.out_room_at,
                       s.surgery_level, s.asa_grade, s.incision_type, s.surgery_kind,
                       s.is_unplanned_reop, s.cancel_stage, s.cancel_reason,
                       a.admission_no, b.bed_no, p.name as patient_name,
                       u.real_name as surgeon_name
                from inp_surgery s
                join inp_admission a on a.id = s.admission_id
                join inp_bed b on b.id = a.bed_id
                join empi_patient p on p.id = a.patient_id
                left join sys_user u on u.id = s.surgeon_id
                where s.id = ?
                """, id);
        if (rows.isEmpty()) return R.fail(4900, "手术记录不存在");
        Map<String, Object> d = new LinkedHashMap<>(rows.get(0));
        d.put("surgeryKindName", SURGERY_KINDS.get(String.valueOf(d.get("surgery_kind"))));
        d.put("cancelStageName", CANCEL_STAGES.get(String.valueOf(d.get("cancel_stage"))));
        Map<String, Instant> tp = new LinkedHashMap<>();
        for (String c : TP_ORDER) tp.put(c, toInstant(d.get(c)));
        d.put("phase", phaseOf(tp));
        return R.ok(d);
    }

    /** 白名单字典：前端下拉的唯一来源，杜绝前后端两份白名单漂移 */
    public R<Map<String, Object>> dict() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("surgeryLevels", SURGERY_LEVELS);
        d.put("asaGrades", ASA_GRADES);
        d.put("incisionTypes", INCISION_TYPES);
        d.put("surgeryKinds", labelList(SURGERY_KINDS));
        d.put("cancelStages", labelList(CANCEL_STAGES));
        d.put("timepointStages", labelList(TIMEPOINT_STAGES));
        return R.ok(d);
    }

    // ===================== 工具 =====================

    private static List<Map<String, String>> labelList(Map<String, String> src) {
        List<Map<String, String>> out = new ArrayList<>(src.size());
        for (var e : src.entrySet()) out.add(Map.of("code", e.getKey(), "name", e.getValue()));
        return out;
    }

    private static String labelOfColumn(String column) {
        for (var e : TP_COLUMN.entrySet()) if (e.getValue().equals(column)) return TIMEPOINT_STAGES.get(e.getKey());
        return column;
    }

    /**
     * 由四个时间点<b>派生</b>进行阶段——刻意不落库、不进 status。
     * WAITING 未入室 / IN_ROOM 已入室未开台 / OPERATING 手术中 / CLOSED 已结束未出室 / OUT 已出室。
     */
    private static String phaseOf(Map<String, Instant> tp) {
        if (tp.get("out_room_at") != null) return "OUT";
        if (tp.get("end_at") != null) return "CLOSED";
        if (tp.get("start_at") != null) return "OPERATING";
        if (tp.get("in_room_at") != null) return "IN_ROOM";
        return "WAITING";
    }

    /** timestamptz 列的取值在不同驱动/映射下可能是 Timestamp / OffsetDateTime / Instant，统一收口 */
    private static Instant toInstant(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp ts) return ts.toInstant();
        if (v instanceof Instant i) return i;
        if (v instanceof OffsetDateTime odt) return odt.toInstant();
        if (v instanceof java.util.Date d) return d.toInstant();
        return null;
    }

    /** 宽容地解析 ISO-8601：带时区 → 原样；不带时区 → 按业务时区（会话时区）解释；"日期 空格 时间"也认 */
    private static Instant parseInstant(String raw) {
        String s = raw.trim();
        if (s.length() > 10 && s.charAt(10) == ' ') s = s.substring(0, 10) + "T" + s.substring(11);
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (RuntimeException ignore) {
            // 继续尝试无时区形态
        }
        try {
            // 无时区的字面量按**业务时区**解释，不用 JVM 默认时区（同 BusinessDates 的理由：
            // 容器 TZ 未生效时 JVM 是 UTC，会把"09:30 开台"记成北京时间 17:30）
            return LocalDateTime.parse(s).atZone(ZoneId.of(HipProfiles.ZONE)).toInstant();
        } catch (RuntimeException ignore) {
            return null;
        }
    }
}
