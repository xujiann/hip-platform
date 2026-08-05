package cn.hip.server.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 三十五期：价格规则维护（调价留痕）与异常交款核查 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class PriceFinanceController {

    private final JdbcTemplate jdbc;

    // ---- 价格规则 ----
    public record PriceReq(BigDecimal newPrice, String reason) {}

    /** 诊疗项目调价：留痕生效 */
    @PutMapping("/api/price/charge-items/{id}")
    @Transactional
    public R<Void> changePrice(@PathVariable Long id, @RequestBody PriceReq req, Authentication auth) {
        if (req.newPrice() == null || req.newPrice().compareTo(BigDecimal.ZERO) < 0) return R.fail(4740, "价格不合法");
        if (req.reason() == null || req.reason().isBlank()) return R.fail(4741, "调价原因必填");
        var rows = jdbc.queryForList("select price from md_charge_item where id = ?", id);
        if (rows.isEmpty()) return R.fail(4742, "项目不存在");
        BigDecimal old = (BigDecimal) rows.get(0).get("price");
        jdbc.update("insert into price_change_log(item_id, old_price, new_price, reason, changed_by) values (?,?,?,?,?)",
                id, old, req.newPrice(), req.reason(), auth.getName());
        jdbc.update("update md_charge_item set price = ? where id = ?", req.newPrice(), id);
        return R.ok();
    }

    @GetMapping("/api/price/change-logs")
    public R<List<Map<String, Object>>> changeLogs() {
        return R.ok(jdbc.queryForList("""
                select l.*, c.code, c.name from price_change_log l
                join md_charge_item c on c.id = l.item_id order by l.id desc limit 100
                """));
    }

    // ---- 异常交款核查 ----
    /** 按收款员核对：收款/退款/净额 + 异常明细（退费单、作废支付单） */
    @GetMapping("/api/finance/reconciliation")
    public R<Map<String, Object>> reconciliation(@RequestParam String date) {
        var m = new LinkedHashMap<String, Object>();
        m.put("byCashier", jdbc.queryForList("""
                select coalesce(u.real_name, u.username, '未记录') as cashier,
                       count(*) filter (where c.status = 'PAID') as paid_cnt,
                       coalesce(sum(c.total_amount) filter (where c.status = 'PAID'), 0) as paid_amount,
                       count(*) filter (where c.status = 'REFUNDED') as refund_cnt,
                       coalesce(sum(c.total_amount) filter (where c.status = 'REFUNDED'), 0) as refund_amount
                from outp_charge c
                left join sys_user u on u.id = c.cashier_id
                where c.created_at::date = ?::date
                group by coalesce(u.real_name, u.username, '未记录')
                """, date));
        m.put("anomalies", jdbc.queryForList("""
                select 'REFUND' as kind, c.charge_no as ref_no, c.total_amount as amount,
                       c.pay_method as detail, c.created_at
                from outp_charge c where c.status = 'REFUNDED' and c.created_at::date = ?::date
                union all
                select 'PAY_CANCEL', p.pay_no, p.amount, p.channel, p.created_at
                from pay_order p where p.status = 'CANCELLED' and p.created_at::date = ?::date
                order by created_at desc
                """, date, date));
        return R.ok(m);
    }
}
