package cn.hip.server.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 三十五期：异常交款核查（跨门诊收费与支付单的财务稽核，留 server 聚合层；价格维护已下沉 masterdata）。
 *
 * <p>1.1.3 口径修正（审阅 B-1/B-2）：收款按**收款日/收款员**、退款按**退费日/退费操作员**分别归集。
 * 原实现两者都按结算单创建日与收款员——D1 收费 D2 退费时 D1 历史报表事后变脸，
 * 且甲窗口收的钱由乙窗口退时账挂在甲头上，交款核查要查的正是这类差异却查不出来。
 * 日期一律半开区间（`>= d and < d+1`）：`created_at::date = ?` 会让索引失效走全表扫（B-4）。
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class FinanceController {

    private final JdbcTemplate jdbc;

    /** 按操作员核对：收款侧（当日经手收款）与退款侧（当日经手退款）各自归集后合并 */
    @GetMapping("/api/finance/reconciliation")
    public R<Map<String, Object>> reconciliation(@RequestParam String date) {
        date = java.time.LocalDate.parse(date).toString();   // 预校验：非法日期转 4000 而非 500（1.2.0）
        var m = new LinkedHashMap<String, Object>();
        m.put("byCashier", jdbc.queryForList("""
                select cashier,
                       sum(paid_cnt)      as paid_cnt,
                       sum(paid_amount)   as paid_amount,
                       sum(refund_cnt)    as refund_cnt,
                       sum(refund_amount) as refund_amount
                from (
                    -- 归并键用 user id 而非显示名（1.2.0）：两位"张伟"会被并成一行，
                    -- 与本接口"账各归各"的目的相悖；展示列另取
                    select u.id as uid,
                           coalesce(u.real_name, u.username, '未记录') as cashier,
                           count(*) as paid_cnt, coalesce(sum(c.total_amount), 0) as paid_amount,
                           0 as refund_cnt, 0::numeric as refund_amount
                    from outp_charge c
                    left join sys_user u on u.id = c.cashier_id
                    where c.created_at >= ?::date and c.created_at < ?::date + interval '1 day'
                    group by u.id, 2
                    union all
                    select u.id, coalesce(u.real_name, u.username, '未记录'),
                           0, 0::numeric,
                           count(*), coalesce(sum(c.total_amount), 0)
                    from outp_charge c
                    left join sys_user u on u.id = c.refund_by
                    where c.status = 'REFUNDED'
                      and c.refunded_at >= ?::date and c.refunded_at < ?::date + interval '1 day'
                    group by u.id, 2
                ) t
                group by t.uid, t.cashier
                order by t.cashier
                """, date, date, date, date));
        m.put("anomalies", jdbc.queryForList("""
                select 'REFUND' as kind, c.charge_no as ref_no, c.total_amount as amount,
                       c.pay_method as detail, c.refunded_at as occurred_at
                from outp_charge c
                where c.status = 'REFUNDED'
                  and c.refunded_at >= ?::date and c.refunded_at < ?::date + interval '1 day'
                union all
                select 'PAY_CANCEL', p.pay_no, p.amount, p.channel, p.created_at
                from pay_order p
                where p.status = 'CANCELLED'
                  and p.created_at >= ?::date and p.created_at < ?::date + interval '1 day'
                union all
                select 'INP_CANCEL', s.settle_no, s.total_amount, s.pay_method, s.refunded_at
                from inp_settlement s
                where s.status = 'CANCELLED'
                  and s.refunded_at >= ?::date and s.refunded_at < ?::date + interval '1 day'
                order by occurred_at desc
                """, date, date, date, date, date, date));
        return R.ok(m);
    }
}
