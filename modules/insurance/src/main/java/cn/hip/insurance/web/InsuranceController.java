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
import cn.hip.platform.core.config.BusinessDates;

/** 二十七期：医保管理——目录对照维护、结算分割查询、审核提醒、对账、汇总；批次三补 CSV 导入与对照率 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/insurance")
public class InsuranceController {

    private final JdbcTemplate jdbc;
    private final InsuranceReconService reconService;
    private final cn.hip.platform.core.service.ConfigReader configReader;

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

    /** 对照表 + 未对照项目提示 + 对照率统计（真实目录数万条：限量 500 + 关键字过滤，1.1.7） */
    @GetMapping("/catalog")
    public R<Map<String, Object>> catalog(@RequestParam(required = false) String keyword) {
        var m = new LinkedHashMap<String, Object>();
        String like = keyword == null || keyword.isBlank() ? null : "%" + keyword.strip() + "%";
        m.put("mapped", jdbc.queryForList("""
                select * from yb_catalog_map
                where ?::text is null or item_name like ? or item_code like ? or yb_code like ?
                order by item_type, item_code limit 500
                """, like, like, like, like));
        m.put("mappedTotal", jdbc.queryForObject("select count(*) from yb_catalog_map", Integer.class));
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
        // 半开区间（B-4）：s.created_at::date = ? 不可 sarg，分割表按日查询走全表扫
        String d = date == null ? cn.hip.platform.core.config.BusinessDates.today().toString() : date;
        return R.ok(jdbc.queryForList("""
                select s.*, coalesce(p.name, '-') as patient_name
                from yb_settle_split s
                left join outp_charge c on c.charge_no = s.charge_no
                left join outp_registration r on r.id = c.registration_id
                left join empi_patient p on p.id = r.patient_id
                where s.created_at >= ?::date and s.created_at < ?::date + interval '1 day'
                order by s.id desc limit 200
                """, d, d));
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
                from outp_charge where pay_method = 'YB' and status = 'PAID'
                  and created_at >= current_date and created_at < current_date + 1
                """));
        m.put("splitToday", jdbc.queryForMap("""
                select coalesce(sum(s.fund_pay), 0) as fund_pay, coalesce(sum(s.self_pay), 0) as self_pay
                from yb_settle_split s
                left join outp_charge c on s.biz_type = 'OUTP' and c.charge_no = s.charge_no
                where s.created_at >= current_date and s.created_at < current_date + 1
                  and (s.biz_type <> 'OUTP' or c.status = 'PAID')
                """));
        m.put("auditWarns", jdbc.queryForObject(
                "select count(*) from yb_audit_log where created_at >= current_date and created_at < current_date + 1",
                Integer.class));
        m.put("mappedCount", jdbc.queryForObject("select count(*) from yb_catalog_map", Integer.class));
        m.put("lastRecon", jdbc.queryForList(
                "select * from yb_recon_batch order by id desc limit 1"));
        return R.ok(m);
    }

    // ---- v41 医保基金使用监测（近 12 个月趋势 + 超封顶线预警，纯只读） ----

    /**
     * 近 12 个月 × biz_type 的结算总额/统筹支付/基金占比。
     *
     * <p><b>排除 reversed = true 的冲销行</b>：退费冲正时 InsuranceSplitService.reverse 会
     * 抢占式打上该标记并回退患者年度累计（分割留痕行本身保留，不物理删除）。基金监测若把冲销行
     * 一起 sum，等于把已经退给医保的钱继续算成基金支出——占比与总额双双虚高。
     * 时间窗写成 `created_at >= ?` 半开区间（列上不套函数），走 idx_yb_split_created（V57 B-9）；
     * 分组用的 date_trunc 只作用在结果集上，不影响索引选择。
     */
    @GetMapping("/fund-monitor")
    public R<Map<String, Object>> fundMonitor() {
        String today = BusinessDates.today().toString();
        var m = new LinkedHashMap<String, Object>();
        m.put("monthly", jdbc.queryForList("""
                select to_char(date_trunc('month', created_at), 'YYYY-MM') as month,
                       biz_type,
                       count(*)                    as bills,
                       coalesce(sum(total), 0)     as total,
                       coalesce(sum(fund_pay), 0)  as fund_pay,
                       coalesce(sum(self_pay), 0)  as self_pay,
                       coalesce(round(sum(fund_pay) * 100 / nullif(sum(total), 0), 1), 0) as fund_ratio
                from yb_settle_split
                where not reversed
                  and created_at >= date_trunc('month', ?::date) - interval '11 months'
                  and created_at <  date_trunc('month', ?::date) + interval '1 month'
                group by 1, 2
                order by 1, 2
                """, today, today));

        // 封顶线：0 = 未启用（V41 默认值）。按 0 判定会让**全部**参保患者 fund_used >= 0 命中，
        // 一开报表就是"全员超封顶"的假警报——故未启用时该项显式返回 null 并注明，不做判定。
        java.math.BigDecimal capStaff = cap("yb_cap_staff");
        java.math.BigDecimal capResident = cap("yb_cap_resident");
        var caps = new LinkedHashMap<String, Object>();
        caps.put("staff", capStaff.signum() > 0 ? capStaff : null);
        caps.put("resident", capResident.signum() > 0 ? capResident : null);
        caps.put("warnAt", "封顶线的 90%");
        m.put("caps", caps);

        int year = BusinessDates.today().getYear();
        m.put("capYear", year);
        if (capStaff.signum() == 0 && capResident.signum() == 0) {
            m.put("capAlerts", null);
            m.put("capAlertNote", "未启用封顶线（sys_config 的 yb_cap_staff / yb_cap_resident 均为 0），不做超限判定");
        } else {
            m.put("capAlerts", jdbc.queryForList("""
                    select p.id as patient_id, p.name as patient_name, p.patient_no,
                           p.insurance_type, a.year, a.fund_used, c.cap,
                           round(a.fund_used * 100 / nullif(c.cap, 0), 1) as used_ratio
                    from yb_patient_annual a
                    join empi_patient p on p.id = a.patient_id
                    join lateral (select case
                              when coalesce(p.insurance_type, '') in ('YB_STAFF', 'YB_EMPLOYEE') then ?::numeric
                              when coalesce(p.insurance_type, '') = 'YB_RESIDENT'                then ?::numeric
                              else 0 end as cap) c on true
                    where a.year = ? and c.cap > 0 and a.fund_used >= c.cap * 0.9
                    order by a.fund_used desc
                    limit 200
                    """, capStaff, capResident, year));
            m.put("capAlertNote", "%s；名单为年度统筹已用达封顶线 90%% 及以上的参保人".formatted(
                    (capStaff.signum() > 0 ? "职工封顶线 " + capStaff.toPlainString() : "职工封顶线未启用")
                            + "，" + (capResident.signum() > 0
                                    ? "居民封顶线 " + capResident.toPlainString() : "居民封顶线未启用")));
        }
        return R.ok(m);
    }

    /**
     * 封顶线读取走 ConfigReader（1.1.6 B-1 缓存口径）。坏值按 0（=未启用）处理：
     * 只读监测宁可少报也不能把全员打成超限——分割服务侧对坏值已有 error 级告警，不在此重复刷屏。
     */
    private java.math.BigDecimal cap(String key) {
        String v = configReader.get(key, "0");
        try {
            return new java.math.BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return java.math.BigDecimal.ZERO;
        }
    }
}
