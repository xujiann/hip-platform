package cn.hip.insurance.web;

import cn.hip.insurance.service.InsuranceReconService;
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

/** 二十七期：医保管理——目录对照维护、结算分割查询、审核提醒、对账、汇总；批次三补 CSV 导入与对照率 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/insurance")
public class InsuranceController {

    private final JdbcTemplate jdbc;
    private final InsuranceReconService reconService;

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

    /** 对照表 + 未对照项目提示 + 对照率统计 */
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
        m.put("stats", jdbc.queryForMap("""
                select
                  (select count(*) from md_drug where enabled) as total_drugs,
                  (select count(*) from md_drug d where enabled and exists
                     (select 1 from yb_catalog_map m where m.item_type = 'DRUG' and m.item_code = d.code)) as mapped_drugs,
                  (select count(*) from md_charge_item where enabled) as total_items,
                  (select count(*) from md_charge_item c where enabled and exists
                     (select 1 from yb_catalog_map m where m.item_type = 'ITEM' and m.item_code = c.code)) as mapped_items
                """));
        return R.ok(m);
    }

    /** 目录对照 CSV 批量导入（实施工具）：列 item_type,item_code,item_name,yb_code,charge_class,self_ratio[,effective_date]
     *  首行为表头则自动跳过；逐行校验，错误行跳过并回报行号原因；合法行 upsert 即时生效 */
    @PostMapping(value = "/catalog/import", consumes = "text/plain")
    @Transactional
    public R<Map<String, Object>> importCatalog(@RequestBody String csv) {
        int ok = 0;
        List<String> errors = new ArrayList<>();
        String[] lines = csv.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) continue;
            if (i == 0 && line.toLowerCase().startsWith("item_type")) continue;
            String[] f = line.split(",", -1);
            if (f.length < 6) {
                errors.add("第" + (i + 1) + "行：列数不足 6");
                continue;
            }
            String itemType = f[0].strip(), itemCode = f[1].strip(), itemName = f[2].strip();
            String ybCode = f[3].strip(), chargeClass = f[4].strip().toUpperCase();
            if (!Set.of("DRUG", "ITEM").contains(itemType)) {
                errors.add("第" + (i + 1) + "行：类型只能为 DRUG/ITEM");
                continue;
            }
            if (!Set.of("A", "B", "C").contains(chargeClass)) {
                errors.add("第" + (i + 1) + "行：类别只能为 A/B/C");
                continue;
            }
            double selfRatio;
            try {
                selfRatio = f[5].strip().isEmpty() ? 0 : Double.parseDouble(f[5].strip());
                if (selfRatio < 0 || selfRatio > 1) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                errors.add("第" + (i + 1) + "行：自付比例须为 0-1 数值");
                continue;
            }
            String effective = f.length > 6 && !f[6].strip().isEmpty() ? f[6].strip() : null;
            jdbc.update("""
                    insert into yb_catalog_map(item_type, item_code, item_name, yb_code, charge_class, self_ratio, effective_date)
                    values (?,?,?,?,?,?, coalesce(?::date, current_date))
                    on conflict (item_type, item_code) do update set yb_code = excluded.yb_code,
                        item_name = excluded.item_name, charge_class = excluded.charge_class,
                        self_ratio = excluded.self_ratio, effective_date = excluded.effective_date, updated_at = now()
                    """, itemType, itemCode, itemName, ybCode, chargeClass, selfRatio, effective);
            ok++;
        }
        return R.ok(Map.of("imported", ok, "errorCount", errors.size(), "errors", errors));
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

    // ---- 对账：业务账（门诊结算/住院结算） vs 医保通道留痕（逻辑在 InsuranceReconService，自动对账任务共用） ----
    @GetMapping("/reconcile")
    public R<Map<String, Object>> reconcile(@RequestParam String date) {
        List<Map<String, Object>> rows = reconService.reconRows(date);
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
    public R<Map<String, Object>> saveReconcile(@RequestParam String date) {
        return R.ok(reconService.reconcileAndSave(date));
    }

    @GetMapping("/reconcile/batches")
    public R<List<Map<String, Object>>> batches() {
        return R.ok(jdbc.queryForList("select * from yb_recon_batch order by id desc limit 50"));
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
                select coalesce(sum(s.fund_pay), 0) as fund_pay, coalesce(sum(s.self_pay), 0) as self_pay
                from yb_settle_split s
                left join outp_charge c on s.biz_type = 'OUTP' and c.charge_no = s.charge_no
                where s.created_at::date = current_date
                  and (s.biz_type <> 'OUTP' or c.status = 'PAID')
                """));
        m.put("auditWarns", jdbc.queryForObject(
                "select count(*) from yb_audit_log where created_at::date = current_date", Integer.class));
        m.put("mappedCount", jdbc.queryForObject("select count(*) from yb_catalog_map", Integer.class));
        m.put("lastRecon", jdbc.queryForList(
                "select * from yb_recon_batch order by id desc limit 1"));
        return R.ok(m);
    }
}
