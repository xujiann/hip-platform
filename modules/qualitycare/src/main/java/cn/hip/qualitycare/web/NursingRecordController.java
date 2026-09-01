package cn.hip.qualitycare.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.platform.integration.signature.SignatureAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v42 车道2：护理记录单 / 日常巡视（nur_record）。
 *
 * <p>诚信补齐——技术偏离表 1268/1708/2427/2429/2431/2408/2480（护理记录单）与
 * 2074/1270/2075（日常巡视）已答「平台已实现」，而此前全仓不存在按患者-时间记录护理观察与
 * 措施的表。本控制器是这批条目的真实实现。
 *
 * <p>三条口径：
 * <ul>
 *   <li><b>巡视与护理观察同表</b>，按 record_kind 区分（OBSERVE 护理观察 / ROUNDS 日常巡视 /
 *       MEASURE 护理措施）——字段完全同构，分表只会让打印/统计/签名三处各写两遍。</li>
 *   <li><b>签名冻结与病历同语义</b>：签名走 SignatureAdapter，已签名不可改（4803），
 *       且**只有记录护士本人可签**（4804）——nurse_id 是 sys_user 外键才做得到这条校验，
 *       varchar 存用户名的老表做不到。</li>
 *   <li><b>本版零写路径挡点</b>：gate-check 是纯只读预检，emr.gate.nursing.record 默认 off，
 *       既有 emr.gate.discharge / emr.gate.archive 一字未动。</li>
 * </ul>
 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','NURSE','QUALITY')")
@RequiredArgsConstructor
public class NursingRecordController {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;
    private final ConfigReader configReader;
    private final SignatureAdapter signatureAdapter;

    /** 护理记录类型：观察 / 巡视 / 措施（与 chk_nur_record_kind 一致） */
    public static final List<String> KINDS = List.of("OBSERVE", "ROUNDS", "MEASURE");

    private static final Map<String, String> KIND_NAMES = Map.of(
            "OBSERVE", "护理观察", "ROUNDS", "日常巡视", "MEASURE", "护理措施");

    // ===== 列表 =====

    /**
     * 按住院记录 + 时间窗 + 记录类型查护理记录（时序正序，与纸面记录单一致）。
     * 已出院患者同样可查（打印与病案需要），出院限制只加在写入侧。
     */
    @GetMapping("/api/nursing/records")
    public R<List<Map<String, Object>>> list(@RequestParam Long admissionId,
                                             @RequestParam(required = false) String kind,
                                             @RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to) {
        if (kind != null && !kind.isBlank() && !KINDS.contains(kind)) {
            return R.fail(4801, "记录类型非法（OBSERVE 护理观察 / ROUNDS 日常巡视 / MEASURE 护理措施）");
        }
        var args = new ArrayList<Object>();
        var sql = new StringBuilder("""
                select r.id, r.admission_id, r.record_time, r.record_kind, r.observation, r.measure,
                       r.effect, r.measure_code, r.nurse_id, u.real_name as nurse_name,
                       (r.signature is not null) as signed, r.signed_at, r.created_at
                from nur_record r
                left join sys_user u on u.id = r.nurse_id
                where r.admission_id = ?
                """);
        args.add(admissionId);
        if (kind != null && !kind.isBlank()) {
            sql.append(" and r.record_kind = ? ");
            args.add(kind);
        }
        if (from != null && !from.isBlank()) {
            Timestamp t = parseTime(from);
            if (t == null) return R.fail(4811, "日期时间格式非法：" + from);
            sql.append(" and r.record_time >= ? ");
            args.add(t);
        }
        if (to != null && !to.isBlank()) {
            Timestamp t = parseEndOfWindow(to);
            if (t == null) return R.fail(4811, "日期时间格式非法：" + to);
            sql.append(" and r.record_time < ? ");
            args.add(t);
        }
        sql.append(" order by r.record_time, r.id limit 500");
        return R.ok(jdbc.queryForList(sql.toString(), args.toArray()));
    }

    // ===== 新增 / 修改 =====

    public record RecordReq(Long admissionId, String recordKind, String recordTime,
                            String observation, String measure, String effect, String measureCode) {}

    /** 新增护理记录（记录人取当前登录护士；record_time 可倒填，夜班补录不吃 now()） */
    @PostMapping("/api/nursing/records")
    public R<Map<String, Object>> add(@RequestBody RecordReq req, Authentication auth) {
        String kind = req.recordKind();
        if (kind == null || !KINDS.contains(kind)) {
            return R.fail(4801, "记录类型非法（OBSERVE 护理观察 / ROUNDS 日常巡视 / MEASURE 护理措施）");
        }
        if (blank(req.observation()) && blank(req.measure())) {
            return R.fail(4802, "病情观察与护理措施不能同时为空");
        }
        var adm = jdbc.queryForList(
                "select status from inp_admission where id = ?", req.admissionId());
        if (adm.isEmpty() || !"IN_HOSPITAL".equals(adm.get(0).get("status"))) {
            return R.fail(4805, "住院记录不存在或已出院");
        }
        Timestamp when = req.recordTime() == null || req.recordTime().isBlank()
                ? Timestamp.from(Instant.now()) : parseTime(req.recordTime());
        if (when == null) return R.fail(4811, "日期时间格式非法：" + req.recordTime());
        Long id = jdbc.queryForObject("""
                insert into nur_record(admission_id, record_time, record_kind, observation, measure,
                                       effect, measure_code, nurse_id)
                values (?,?,?,?,?,?,?,?) returning id
                """, Long.class, req.admissionId(), when, kind, trimToNull(req.observation()),
                trimToNull(req.measure()), trimToNull(req.effect()), trimToNull(req.measureCode()),
                currentUserService.idOf(auth));
        return R.ok(Map.of("id", id));
    }

    /**
     * 修改护理记录：**已签名不可改**（4803）——与病历签名冻结同语义。
     * 签名后要补正走 v43 的 emr_amendment 通路（需先放宽 V80 的 CHECK），本版不做。
     */
    @PutMapping("/api/nursing/records/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody RecordReq req) {
        var rows = jdbc.queryForList("select signature, record_kind from nur_record where id = ?", id);
        if (rows.isEmpty()) return R.fail(4800, "护理记录不存在");
        if (rows.get(0).get("signature") != null) return R.fail(4803, "护理记录已签名，不可修改");
        String kind = req.recordKind() == null || req.recordKind().isBlank()
                ? (String) rows.get(0).get("record_kind") : req.recordKind();
        if (!KINDS.contains(kind)) {
            return R.fail(4801, "记录类型非法（OBSERVE 护理观察 / ROUNDS 日常巡视 / MEASURE 护理措施）");
        }
        if (blank(req.observation()) && blank(req.measure())) {
            return R.fail(4802, "病情观察与护理措施不能同时为空");
        }
        Timestamp when = req.recordTime() == null || req.recordTime().isBlank()
                ? null : parseTime(req.recordTime());
        if (req.recordTime() != null && !req.recordTime().isBlank() && when == null) {
            return R.fail(4811, "日期时间格式非法：" + req.recordTime());
        }
        jdbc.update("""
                update nur_record set record_kind = ?, observation = ?, measure = ?, effect = ?,
                       measure_code = ?, record_time = coalesce(?, record_time)
                where id = ? and signature is null
                """, kind, trimToNull(req.observation()), trimToNull(req.measure()),
                trimToNull(req.effect()), trimToNull(req.measureCode()), when, id);
        return R.ok();
    }

    // ===== 签名 =====

    /**
     * 护理记录 CA 签名（复用 SignatureAdapter，与住院病历 InpEmrController 同语义）。
     * <b>只有记录护士本人可签</b>（4804）：护理文书的法定意义就在于「谁做的谁签」，
     * 代签等于把法定责任转嫁给未在场的人。
     */
    @PostMapping("/api/nursing/records/{id}/sign")
    public R<Map<String, Object>> sign(@PathVariable Long id, Authentication auth) {
        var rows = jdbc.queryForList(
                "select nurse_id, signature, observation, measure, effect from nur_record where id = ?", id);
        if (rows.isEmpty()) return R.fail(4800, "护理记录不存在");
        var row = rows.get(0);
        if (row.get("signature") != null) return R.fail(4808, "护理记录已签名，不可重复签名");
        Long me = currentUserService.idOf(auth);
        Object nurseId = row.get("nurse_id");
        if (me == null || nurseId == null || !me.equals(((Number) nurseId).longValue())) {
            return R.fail(4804, "只有记录护士本人可以签名");
        }
        String content = String.join("|",
                String.valueOf(row.get("observation")), String.valueOf(row.get("measure")),
                String.valueOf(row.get("effect")));
        var result = signatureAdapter.sign(content, me);
        if (!result.ok()) return R.fail(4809, "签名失败: " + result.message());
        Instant now = Instant.now();
        jdbc.update("update nur_record set signature = ?, signed_at = ? where id = ? and signature is null",
                result.signature(), Timestamp.from(now), id);
        return R.ok(Map.of("signature", result.signature(), "signedAt", now));
    }

    // ===== 只读预检（不挂任何写路径）=====

    /**
     * 护理文书完整性只读预检。
     *
     * <p><b>刻意不接入 emr.gate.discharge / emr.gate.archive</b>：那两个 gate 挂在出院结算与
     * 归档两个真实闭合点上，既有单测与 E2E 对缺项文案逐字断言，塞进去会改既有契约。
     * 本项另开 {@code emr.gate.nursing.record}，且默认 <b>off 而非 warn</b>——护理记录是本版
     * 才落库的新数据，历史住院一条没有，warn 会让每个在院患者的出院提示常亮一条无法自救的缺项。
     * 数据沉淀一段时间后由实施方按院内规范改 warn/block（block 挡点留待后续版本实现）。
     */
    @GetMapping("/api/nursing/records/gate-check")
    public R<Map<String, Object>> gateCheck(@RequestParam Long admissionId) {
        String gate = configReader.get("emr.gate.nursing.record", "off");
        var counts = jdbc.queryForMap("""
                select count(*) filter (where record_kind = 'OBSERVE') as observe_count,
                       count(*) filter (where record_kind = 'ROUNDS')  as rounds_count,
                       count(*) filter (where record_kind = 'MEASURE') as measure_count,
                       count(*)                                        as total,
                       count(*) filter (where signature is null)       as unsigned_count
                from nur_record where admission_id = ?
                """, admissionId);
        var missing = new ArrayList<String>();
        if (((Number) counts.get("total")).longValue() == 0) missing.add("无任何护理记录");
        if (((Number) counts.get("rounds_count")).longValue() == 0) missing.add("无日常巡视记录");
        if (((Number) counts.get("unsigned_count")).longValue() > 0) {
            missing.add("有 " + counts.get("unsigned_count") + " 条护理记录未签名");
        }
        var m = new LinkedHashMap<String, Object>(counts);
        m.put("admissionId", admissionId);
        m.put("gate", gate);
        m.put("missing", missing);
        m.put("complete", missing.isEmpty());
        m.put("note", "本项为只读预检，未挂出院/归档挡点；emr.gate.nursing.record 默认 off");
        return R.ok(m);
    }

    // ===== 打印数据集 =====

    /**
     * 护理记录单打印数据集（前端 PrintView 的 {@code type=nur-record} 分支消费）。
     * 与既有 inp-daily-fee / inp-discharge-summary 同形状：页眉 join + rows 明细。
     * 已出院/已归档患者同样可打（病案装订需要），故不做在院校验。
     */
    @GetMapping("/api/inpatient/admissions/{id}/print/nursing-record")
    public R<Map<String, Object>> printNursingRecord(@PathVariable Long id,
                                                     @RequestParam(required = false) String from,
                                                     @RequestParam(required = false) String to,
                                                     @RequestParam(required = false) String kind) {
        var head = jdbc.queryForList("""
                select a.admission_no, a.care_level, a.admit_at, a.discharged_at,
                       p.name as patient_name, p.patient_no, p.sex, p.birth_date,
                       cd.name as dept_name, wd.name as ward_name, b.bed_no
                from inp_admission a
                join empi_patient p on p.id = a.patient_id
                left join sys_dept cd on cd.id = a.dept_id
                left join sys_dept wd on wd.id = a.ward_id
                left join inp_bed  b  on b.id = a.bed_id
                where a.id = ?
                """, id);
        if (head.isEmpty()) return R.fail(4805, "住院记录不存在");
        var listed = list(id, kind, from, to);
        if (listed.getCode() != 0) return R.fail(listed.getCode(), listed.getMessage());
        var rows = new ArrayList<Map<String, Object>>();
        for (var r : listed.getData()) {
            var one = new LinkedHashMap<String, Object>(r);
            one.put("kind_name", KIND_NAMES.getOrDefault(String.valueOf(r.get("record_kind")),
                    String.valueOf(r.get("record_kind"))));
            rows.add(one);
        }
        var m = new LinkedHashMap<String, Object>(head.get(0));
        m.put("admissionId", id);
        m.put("from", from);
        m.put("to", to);
        m.put("rows", rows);
        return R.ok(m);
    }

    // ===== 工具 =====

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * 宽容解析前端时间：ISO 带时区 / ISO 本地 / "yyyy-MM-dd HH:mm[:ss]" / 纯日期。
     * 解析不出返回 null，由调用方回 4811——不静默吃掉，否则倒填时间会悄悄变成 now()。
     */
    static Timestamp parseTime(String v) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim();
        try {
            return Timestamp.from(OffsetDateTime.parse(s).toInstant());
        } catch (Exception ignored) {
            // 继续尝试本地时间格式
        }
        String iso = s.contains(" ") ? s.replace(' ', 'T') : s;
        try {
            return Timestamp.from(LocalDateTime.parse(iso).atZone(ZoneId.systemDefault()).toInstant());
        } catch (Exception ignored) {
            // 继续尝试纯日期
        }
        try {
            return Timestamp.from(LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 时间窗右端：纯日期按「当日 24 点」闭合（护士填 to=今天要包含今天），带时刻则原样 */
    static Timestamp parseEndOfWindow(String v) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim();
        try {
            return Timestamp.from(LocalDate.parse(s).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (Exception ignored) {
            return parseTime(s);
        }
    }
}
