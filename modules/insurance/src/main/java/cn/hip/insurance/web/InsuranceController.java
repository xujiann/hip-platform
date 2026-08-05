package cn.hip.insurance.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 二十七期：医保管理——目录对照维护、结算分割查询、审核提醒、对账、汇总 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/insurance")
public class InsuranceController {

    private final JdbcTemplate jdbc;

    // ---- 目录对照 ----
    public record MapReq(String itemType, String itemCode, String itemName,
                         String ybCode, String chargeClass, Double selfRatio) {}

    @PostMapping("/catalog")
    public R<Void> upsertMapping(@RequestBody MapReq req) {
        if (!Set.of("DRUG", "ITEM").contains(req.itemType())) return R.fail(4640, "类型只能为 DRUG/ITEM");
        if (!Set.of("A", "B", "C").contains(req.chargeClass())) return R.fail(4641, "类别只能为 A/B/C");
        jdbc.update("""
                insert into yb_catalog_map(item_type, item_code, item_name, yb_code, charge_class, self_ratio)
                values (?,?,?,?,?,?)
                on conflict (item_type, item_code) do update set yb_code = excluded.yb_code,
                    charge_class = excluded.charge_class, self_ratio = excluded.self_ratio, updated_at = now()
                """, req.itemType(), req.itemCode(), req.itemName(), req.ybCode(), req.chargeClass(),
                req.selfRatio() == null ? 0 : req.selfRatio());
        return R.ok();
    }

    /** 对照表 + 未对照项目提示 */
    @GetMapping("/catalog")
    public R<Map<String, Object>> catalog() {
        var m = new LinkedHashMap<String, Object>();
        m.put("mapped", jdbc.queryForList("select * from yb_catalog_map order by item_type, item_code"));
        m.put("unmappedDrugs", jdbc.queryForList("""
                select code, name from md_drug d where enabled
                  and not exists (select 1 from yb_catalog_map m where m.item_type = 'DRUG' and m.item_code = d.code)
                order by code
                """));
        m.put("unmappedItems", jdbc.queryForList("""
                select code, name from md_charge_item c where enabled
                  and not exists (select 1 from yb_catalog_map m where m.item_type = 'ITEM' and m.item_code = c.code)
                order by code
                """));
        return R.ok(m);
    }

    // ---- 结算分割 ----
    @GetMapping("/splits")
    public R<List<Map<String, Object>>> splits(@RequestParam(required = false) String date) {
        String d = date == null ? "current_date" : "?::date";
        String sql = """
                select s.*, coalesce(p.name, '-') as patient_name
                from yb_settle_split s
                left join outp_charge c on c.charge_no = s.charge_no
                left join outp_registration r on r.id = c.registration_id
                left join empi_patient p on p.id = r.patient_id
                where s.created_at::date = %s order by s.id desc limit 200
                """.formatted(d);
        return R.ok(date == null ? jdbc.queryForList(sql) : jdbc.queryForList(sql, date));
    }

    // ---- 审核提醒 ----
    @GetMapping("/audits")
    public R<List<Map<String, Object>>> audits() {
        return R.ok(jdbc.queryForList("select * from yb_audit_log order by id desc limit 200"));
    }

    // ---- 对账：业务账（门诊结算/住院结算） vs 医保通道留痕 ----
    @GetMapping("/reconcile")
    public R<Map<String, Object>> reconcile(@RequestParam String date) {
        List<Map<String, Object>> rows = reconRows(date);
        long matched = rows.stream().filter(r -> (Boolean) r.get("consistent")).count();
        var m = new LinkedHashMap<String, Object>();
        m.put("rows", rows);
        m.put("total", rows.size());
        m.put("matched", matched);
        m.put("diff", rows.size() - matched);
        return R.ok(m);
    }

    /** 保存对账批次留痕 */
    @PostMapping("/reconcile")
    @Transactional
    public R<Map<String, Object>> saveReconcile(@RequestParam String date) {
        List<Map<String, Object>> rows = reconRows(date);
        long matched = rows.stream().filter(r -> (Boolean) r.get("consistent")).count();
        String diffDetail = rows.stream().filter(r -> !(Boolean) r.get("consistent"))
                .map(r -> String.valueOf(r.get("charge_no"))).reduce((x, y) -> x + "," + y).orElse("");
        jdbc.update("""
                insert into yb_recon_batch(recon_date, total_cnt, matched_cnt, diff_cnt, detail)
                values (?::date,?,?,?,?)
                """, date, rows.size(), matched, rows.size() - matched, diffDetail);
        return R.ok(Map.of("total", rows.size(), "matched", matched, "diff", rows.size() - matched));
    }

    @GetMapping("/reconcile/batches")
    public R<List<Map<String, Object>>> batches() {
        return R.ok(jdbc.queryForList("select * from yb_recon_batch order by id desc limit 50"));
    }

    private List<Map<String, Object>> reconRows(String date) {
        List<Map<String, Object>> out = new ArrayList<>();
        // 门诊：YB 结算单 ←→ 通道 settle/refund 报文
        for (var c : jdbc.queryForList("""
                select charge_no, total_amount, status from outp_charge
                where pay_method = 'YB' and created_at::date = ?::date order by id
                """, date)) {
            String no = (String) c.get("charge_no");
            boolean hasSettle = msgExists(no, "outpatient.settle") || msgExists(no, "inpatient.settle");
            boolean hasRefund = msgExists(no, "outpatient.refund");
            boolean refunded = "REFUNDED".equals(c.get("status"));
            boolean consistent = refunded ? hasSettle && hasRefund : hasSettle;
            out.add(reconRow("OUTP", no, c.get("total_amount"), (String) c.get("status"),
                    hasSettle, hasRefund, consistent));
        }
        // 住院：YB 出院结算 ←→ 通道 settle 报文
        for (var s : jdbc.queryForList("""
                select settle_no, total_amount from inp_settlement
                where pay_method = 'YB' and created_at::date = ?::date order by id
                """, date)) {
            String no = (String) s.get("settle_no");
            boolean hasSettle = msgExists(no, "settle");
            out.add(reconRow("INP", no, s.get("total_amount"), "PAID", hasSettle, false, hasSettle));
        }
        return out;
    }

    private boolean msgExists(String refNo, String apiKeyword) {
        Integer n = jdbc.queryForObject("""
                select count(*) from int_message_log
                where channel = 'YB' and status = 'OK' and ref_no = ? and payload like ?
                """, Integer.class, refNo, "%" + apiKeyword + "%");
        return n != null && n > 0;
    }

    private Map<String, Object> reconRow(String bizType, String no, Object amount, String status,
                                         boolean hasSettle, boolean hasRefund, boolean consistent) {
        var r = new LinkedHashMap<String, Object>();
        r.put("biz_type", bizType);
        r.put("charge_no", no);
        r.put("amount", amount);
        r.put("local_status", status);
        r.put("has_settle_msg", hasSettle);
        r.put("has_refund_msg", hasRefund);
        r.put("consistent", consistent);
        return r;
    }

    // ---- 医保汇总 ----
    @GetMapping("/summary")
    public R<Map<String, Object>> summary() {
        var m = new LinkedHashMap<String, Object>();
        m.put("outpToday", jdbc.queryForMap("""
                select count(*) as cnt, coalesce(sum(total_amount), 0) as amount
                from outp_charge where pay_method = 'YB' and status = 'PAID' and created_at::date = current_date
                """));
        m.put("splitToday", jdbc.queryForMap("""
                select coalesce(sum(fund_pay), 0) as fund_pay, coalesce(sum(self_pay), 0) as self_pay
                from yb_settle_split where created_at::date = current_date
                """));
        m.put("auditWarns", jdbc.queryForObject(
                "select count(*) from yb_audit_log where created_at::date = current_date", Integer.class));
        m.put("mappedCount", jdbc.queryForObject("select count(*) from yb_catalog_map", Integer.class));
        m.put("lastRecon", jdbc.queryForList(
                "select * from yb_recon_batch order by id desc limit 1"));
        return R.ok(m);
    }
}
