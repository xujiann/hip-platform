package cn.hip.server.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** 三十五期：异常交款核查（跨门诊收费与支付单的财务稽核，留 server 聚合层；价格维护已下沉 masterdata） */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class FinanceController {

    private final JdbcTemplate jdbc;

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
