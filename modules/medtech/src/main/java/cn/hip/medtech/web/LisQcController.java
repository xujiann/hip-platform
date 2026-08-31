package cn.hip.medtech.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import cn.hip.platform.core.service.ConfigReader;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v36 LIS 质控轮：微生物药敏结构化 / 室内质控 IQC(Westgard) / 结果 Delta check / TAT 周转统计。
 * delta/tat 纯只读，micro/qc 独立表——均与患者结果发布链路（LabResultReceivedEvent）无耦合。
 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN','DOCTOR_OUTP','NURSE')")
@RequiredArgsConstructor
public class LisQcController {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;
    private final ConfigReader configReader;

    // ===== ① 微生物培养 + 药敏 =====

    public record AstLine(String antibiotic, String method, String micValue, String sir) {}
    public record MicroReq(String specimen, String organism, String gram, String colonyCount, List<AstLine> ast) {}

    /** 待录微生物工作队列：已核收标本 */
    @GetMapping("/api/lis/micro/samples")
    public R<List<Map<String, Object>>> microSamples() {
        return R.ok(jdbc.queryForList("""
                select s.id, s.barcode, s.order_id, o.item_name, p.name as patient_name
                from lis_sample s join outp_order o on o.id = s.order_id
                join outp_registration r on r.id = o.registration_id
                join empi_patient p on p.id = r.patient_id
                where s.status = 'RECEIVED' order by s.id
                """));
    }

    @PostMapping("/api/lis/micro/{barcode}")
    @Transactional
    public R<Void> micro(@PathVariable String barcode, @RequestBody MicroReq req, Authentication auth) {
        var rows = jdbc.queryForList(
                "select id, order_id from lis_sample where barcode = ? and status = 'RECEIVED'", barcode);
        if (rows.isEmpty()) return R.fail(7106, "标本不存在或未核收");
        if (req.organism() == null || req.organism().isBlank()) return R.fail(7107, "菌种名必填");
        if (req.ast() != null) {
            for (var a : req.ast()) {
                if (a.antibiotic() == null || a.antibiotic().isBlank()
                        || a.sir() == null || !Set.of("S", "I", "R").contains(a.sir())) {
                    return R.fail(7108, "药敏行非法（抗菌药必填，SIR 只能为 S/I/R）");
                }
            }
        }
        Long sampleId = ((Number) rows.get(0).get("id")).longValue();
        Long orderId = ((Number) rows.get(0).get("order_id")).longValue();
        var kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    insert into lab_micro_result(sample_id, order_id, specimen, organism, colony_count, gram, reporter_id)
                    values (?,?,?,?,?,?,?)
                    """, new String[]{"id"});
            ps.setLong(1, sampleId);
            ps.setLong(2, orderId);
            ps.setString(3, req.specimen());
            ps.setString(4, req.organism());
            ps.setString(5, req.colonyCount());
            ps.setString(6, req.gram());
            ps.setObject(7, currentUserService.idOf(auth));
            return ps;
        }, kh);
        long microId = kh.getKey().longValue();
        if (req.ast() != null) {
            for (var a : req.ast()) {
                jdbc.update("insert into lab_micro_ast(micro_id, antibiotic, method, mic_value, sir) values (?,?,?,?,?)",
                        microId, a.antibiotic(), a.method(), a.micValue(), a.sir());
            }
        }
        return R.ok();
    }

    @GetMapping("/api/lis/micro")
    public R<List<Map<String, Object>>> microResults(@RequestParam Long orderId) {
        var micros = jdbc.queryForList("select * from lab_micro_result where order_id = ? order by id", orderId);
        for (var m : micros) {
            m.put("ast", jdbc.queryForList("select antibiotic, method, mic_value, sir from lab_micro_ast where micro_id = ? order by id",
                    ((Number) m.get("id")).longValue()));
        }
        return R.ok(micros);
    }

    // ===== ② 室内质控 IQC（Westgard） =====

    public record QcReq(String itemCode, String level, String lotNo,
                        BigDecimal targetValue, BigDecimal sd, BigDecimal measuredValue) {}

    @PostMapping("/api/lis/qc")
    public R<Map<String, Object>> qc(@RequestBody QcReq req, Authentication auth) {
        if (req.itemCode() == null || req.itemCode().isBlank() || req.level() == null || req.level().isBlank()
                || req.lotNo() == null || req.lotNo().isBlank() || req.targetValue() == null || req.measuredValue() == null) {
            return R.fail(7109, "质控项目/水平/批号/靶值/实测值必填");
        }
        if (req.sd() == null || req.sd().compareTo(BigDecimal.ZERO) <= 0) return R.fail(7110, "标准差 SD 必须大于 0");
        double z = req.measuredValue().subtract(req.targetValue()).doubleValue() / req.sd().doubleValue();
        String rule = null;
        boolean inControl = true;
        if (Math.abs(z) > 3) {
            rule = "1-3s";
            inControl = false;
        } else if (Math.abs(z) > 2) {
            // 2-2s：上一条同项目同水平也 >2SD 且同侧 → 失控；否则仅 1-2s 警告（仍在控）
            var last = jdbc.queryForList(
                    "select z_score from lab_qc_record where item_code = ? and level = ? order by id desc limit 1",
                    req.itemCode(), req.level());
            if (!last.isEmpty() && last.get(0).get("z_score") != null) {
                double pz = ((Number) last.get(0).get("z_score")).doubleValue();
                if (Math.abs(pz) > 2 && Math.signum(pz) == Math.signum(z)) {
                    rule = "2-2s";
                    inControl = false;
                }
            }
            if (rule == null) rule = "1-2s(警告)";
        }
        double zr = Math.round(z * 1000) / 1000.0;
        jdbc.update("""
                insert into lab_qc_record(item_code, level, lot_no, target_value, sd, measured_value,
                        z_score, rule_broken, in_control, operator_id)
                values (?,?,?,?,?,?,?,?,?,?)
                """, req.itemCode(), req.level(), req.lotNo(), req.targetValue(), req.sd(), req.measuredValue(),
                zr, rule, inControl, currentUserService.idOf(auth));
        return R.ok(Map.of("inControl", inControl, "zScore", zr, "rule", rule == null ? "" : rule));
    }

    /** Levey-Jennings 序列（供前端画图） */
    @GetMapping("/api/lis/qc/lj")
    public R<List<Map<String, Object>>> qcLj(@RequestParam String itemCode, @RequestParam String level) {
        return R.ok(jdbc.queryForList("""
                select measured_at, measured_value, target_value, sd, z_score, rule_broken, in_control
                from lab_qc_record where item_code = ? and level = ? order by id
                """, itemCode, level));
    }

    /** 各质控项目最新在控状态卡片 */
    @GetMapping("/api/lis/qc/latest")
    public R<List<Map<String, Object>>> qcLatest() {
        return R.ok(jdbc.queryForList("""
                select distinct on (item_code, level) item_code, level, lot_no, measured_value,
                       z_score, rule_broken, in_control, measured_at
                from lab_qc_record order by item_code, level, id desc
                """));
    }

    // ===== ③ Delta check + 趋势 =====

    @GetMapping("/api/lis/delta")
    public R<List<Map<String, Object>>> delta(@RequestParam Long orderId) {
        double threshold = configReader.getInt("lab.delta.threshold.pct", 50);
        var pid = jdbc.queryForList(
                "select r.patient_id from outp_order o join outp_registration r on r.id = o.registration_id where o.id = ?",
                Long.class, orderId);
        if (pid.isEmpty()) return R.ok(List.of());
        Long patientId = pid.get(0);
        var results = jdbc.queryForList(
                "select item_code, item_name, result_value from outp_lab_result where order_id = ? order by id", orderId);
        var out = new java.util.ArrayList<Map<String, Object>>();
        for (var r : results) {
            var row = new java.util.LinkedHashMap<String, Object>();
            row.put("itemName", r.get("item_name"));
            row.put("current", r.get("result_value"));
            Double cur = parseNum((String) r.get("result_value"));
            var prev = jdbc.queryForList("""
                    select lr.result_value from outp_lab_result lr join outp_order o on o.id = lr.order_id
                    join outp_registration reg on reg.id = o.registration_id
                    where reg.patient_id = ? and lr.item_code = ? and lr.order_id <> ?
                    order by lr.id desc limit 1
                    """, patientId, r.get("item_code"), orderId);
            if (cur != null && !prev.isEmpty()) {
                Double last = parseNum((String) prev.get(0).get("result_value"));
                if (last != null && last != 0) {
                    double pct = Math.round((cur - last) / Math.abs(last) * 1000) / 10.0;
                    row.put("previous", last);
                    row.put("changePct", pct);
                    row.put("exceeded", Math.abs(pct) > threshold);
                }
            }
            out.add(row);
        }
        return R.ok(out);
    }

    @GetMapping("/api/lis/trend")
    public R<List<Map<String, Object>>> trend(@RequestParam Long patientId, @RequestParam String itemCode) {
        return R.ok(jdbc.queryForList("""
                select lr.result_value, lr.unit, lr.abnormal_flag, lr.id
                from outp_lab_result lr join outp_order o on o.id = lr.order_id
                join outp_registration r on r.id = o.registration_id
                where r.patient_id = ? and lr.item_code = ? order by lr.id
                """, patientId, itemCode));
    }

    // ===== ④ TAT 周转统计 =====

    @GetMapping("/api/lis/tat")
    public R<Map<String, Object>> tat(@RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to) {
        int limit = configReader.getInt("lab.tat.limit.minutes", 120);
        var where = new StringBuilder(" where s.published_at is not null ");
        var args = new java.util.ArrayList<Object>();
        if (from != null && !from.isBlank()) { where.append(" and s.collected_at >= ?::date "); args.add(from); }
        if (to != null && !to.isBlank()) { where.append(" and s.collected_at < (?::date + 1) "); args.add(to); }
        var m = jdbc.queryForMap("""
                select count(*) as total,
                       round(avg(extract(epoch from (s.received_at - s.collected_at)) / 60)) as collect_to_receive_min,
                       round(avg(extract(epoch from (s.published_at - s.received_at)) / 60)) as receive_to_publish_min,
                       round(avg(extract(epoch from (s.published_at - s.collected_at)) / 60)) as total_tat_min,
                       count(*) filter (where extract(epoch from (s.published_at - s.collected_at)) / 60 > ?) as overtime
                from lis_sample s
                """ + where, concat(limit, args));
        m.put("limitMinutes", limit);
        return R.ok(m);
    }

    @GetMapping("/api/lis/tat/outliers")
    public R<List<Map<String, Object>>> tatOutliers() {
        int limit = configReader.getInt("lab.tat.limit.minutes", 120);
        return R.ok(jdbc.queryForList("""
                select s.barcode, o.item_name, s.collected_at, s.published_at,
                       round(extract(epoch from (s.published_at - s.collected_at)) / 60) as tat_min
                from lis_sample s join outp_order o on o.id = s.order_id
                where s.published_at is not null
                  and extract(epoch from (s.published_at - s.collected_at)) / 60 > ?
                order by tat_min desc limit 100
                """, limit));
    }

    private static Object[] concat(Object head, List<Object> tail) {
        var a = new java.util.ArrayList<Object>();
        a.add(head);
        a.addAll(tail);
        return a.toArray();
    }

    private static Double parseNum(String s) {
        if (s == null) return null;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
