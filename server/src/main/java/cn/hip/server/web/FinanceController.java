package cn.hip.server.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 三十五期：异常交款核查（跨门诊收费与支付单的财务稽核，留 server 聚合层；价格维护已下沉 masterdata）。
 *
 * <p>1.1.3 口径修正（审阅 B-1/B-2）：收款按**收款日/收款员**、退款按**退费日/退费操作员**分别归集。
 * 原实现两者都按结算单创建日与收款员——D1 收费 D2 退费时 D1 历史报表事后变脸，
 * 且甲窗口收的钱由乙窗口退时账挂在甲头上，交款核查要查的正是这类差异却查不出来。
 * 日期一律半开区间（`>= d and < d+1`）：`created_at::date = ?` 会让索引失效走全表扫（B-4）。
 *
 * <p>v41 追加收费员班结缴款单（{@code fin_cashier_shift}）：把"系统应收"与"实际点钞"对上、
 * 差额留痕、财务确认。班结的系统数**不另写一套 SQL**，与 reconciliation 共用
 * {@link #CASHIER_DAY_ROWS}——口径不一致正是 1.1.3 已经付过学费的那类事故。
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class FinanceController {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUser;

    /**
     * 收款员当日收/退归集的**唯一口径来源**（1.1.3 定案，v41 抽出复用）。
     *
     * <p>收款侧按 {@code created_at} 落当日 + {@code cashier_id} 归集；
     * 退费侧按 {@code refunded_at} 落当日 + {@code refund_by} 归集。两侧各出一组行后 union，
     * 由调用方决定是全院汇总（reconciliation）还是只看某一位收费员（班结 preview）。
     *
     * <p>SQL 只此一份是刻意的：交款核查与班结缴款单若各写各的 where，
     * 早晚会一个按收款日、一个按结算单日，收费员拿着两张对不上的单子来找财务——
     * 1.1.3 修的就是这个病，不能在新功能上复发。
     *
     * <p>参数顺序（共 6 个）：date, date, cashierId, date, date, cashierId。
     * cashierId 传 null 表示不过滤（全院）；{@code ?::bigint is null} 的写法让
     * JdbcTemplate 传 null 时 PG 也能定出参数类型。
     */
    private static final String CASHIER_DAY_ROWS = """
                -- 归并键用 user id 而非显示名（1.2.0）：两位"张伟"会被并成一行，
                -- 与本接口"账各归各"的目的相悖；展示列另取
                select u.id as uid,
                       coalesce(u.real_name, u.username, '未记录') as cashier,
                       count(*) as paid_cnt, coalesce(sum(c.total_amount), 0) as paid_amount,
                       0 as refund_cnt, 0::numeric as refund_amount
                from outp_charge c
                left join sys_user u on u.id = c.cashier_id
                where c.created_at >= ?::date and c.created_at < ?::date + interval '1 day'
                  and (?::bigint is null or c.cashier_id = ?::bigint)
                group by u.id, 2
                union all
                select u.id, coalesce(u.real_name, u.username, '未记录'),
                       0, 0::numeric,
                       count(*), coalesce(sum(c.total_amount), 0)
                from outp_charge c
                left join sys_user u on u.id = c.refund_by
                where c.status = 'REFUNDED'
                  and c.refunded_at >= ?::date and c.refunded_at < ?::date + interval '1 day'
                  and (?::bigint is null or c.refund_by = ?::bigint)
                group by u.id, 2
            """;

    /** 按操作员核对：收款侧（当日经手收款）与退款侧（当日经手退款）各自归集后合并 */
    @GetMapping("/api/finance/reconciliation")
    public R<Map<String, Object>> reconciliation(@RequestParam String date) {
        date = java.time.LocalDate.parse(date).toString();   // 预校验：非法日期转 4000 而非 500（1.2.0）
        Long allCashiers = null;                             // null = 不按收款员过滤，出全院各行
        var m = new LinkedHashMap<String, Object>();
        m.put("byCashier", jdbc.queryForList("""
                select cashier,
                       sum(paid_cnt)      as paid_cnt,
                       sum(paid_amount)   as paid_amount,
                       sum(refund_cnt)    as refund_cnt,
                       sum(refund_amount) as refund_amount
                from (
                """ + CASHIER_DAY_ROWS + """
                ) t
                group by t.uid, t.cashier
                order by t.cashier
                """, date, date, allCashiers, allCashiers, date, date, allCashiers, allCashiers));
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
                where s.status = 'CANCELLED' and s.settle_type = 'FINAL'
                  and s.refunded_at >= ?::date and s.refunded_at < ?::date + interval '1 day'
                order by occurred_at desc
                """, date, date, date, date, date, date));
        return R.ok(m);
    }

    // ==================== v41 收费员班结缴款单 ====================
    // 错误码 5020–5024（docs/错误码分段.md 已登记）

    /**
     * 某收费员某日的系统数（sysPaid/sysRefund/sysNet）。与 reconciliation 同源同口径。
     * 返回值特意用与班结单一致的字段名，避免前端在两个名字之间做映射时把口径搞混。
     */
    private Map<String, Object> systemTotals(String date, Long cashierId) {
        var row = jdbc.queryForMap("""
                select coalesce(sum(paid_amount), 0)   as sys_paid,
                       coalesce(sum(refund_amount), 0) as sys_refund
                from (
                """ + CASHIER_DAY_ROWS + """
                ) t
                """, date, date, cashierId, cashierId, date, date, cashierId, cashierId);
        BigDecimal paid = (BigDecimal) row.get("sys_paid");
        BigDecimal refund = (BigDecimal) row.get("sys_refund");
        var m = new LinkedHashMap<String, Object>();
        m.put("sysPaid", paid);
        m.put("sysRefund", refund);
        m.put("sysNet", paid.subtract(refund));
        return m;
    }

    /** 日期参数统一入口：不传取业务今天；非法日期在此转 4000 而非 500 */
    private static String normalizeDate(String date) {
        return date == null || date.isBlank()
                ? BusinessDates.today().toString()
                : java.time.LocalDate.parse(date).toString();
    }

    private static boolean isAdmin(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    /**
     * 班结预览：按**当前登录收费员**算当日系统数，并带出已存在的班结单（前端据此禁用重复提交）。
     * 不接受 cashierId 入参——收费员看别人的应收现金没有业务理由，能传就早晚会被传。
     */
    @GetMapping("/api/finance/shift-close/preview")
    @PreAuthorize("hasAnyRole('ADMIN','CASHIER')")
    public R<Map<String, Object>> shiftClosePreview(@RequestParam(required = false) String date,
                                                    Authentication auth) {
        String d = normalizeDate(date);
        Long cashierId = currentUser.idOf(auth);
        if (cashierId == null) {
            return R.fail(5024, "无法识别当前登录收费员账号，请重新登录后再试");
        }
        var m = new LinkedHashMap<String, Object>();
        m.put("shiftDate", d);
        m.put("cashierId", cashierId);
        m.putAll(systemTotals(d, cashierId));
        // 已提交/已确认的当日班结：前端要据此显示状态并禁用提交
        m.put("existing", jdbc.queryForList("""
                select id, status, declared_cash, diff, note, submitted_at, confirmed_at
                from fin_cashier_shift where cashier_id = ? and shift_date = ?::date
                """, cashierId, d).stream().findFirst().orElse(null));
        return R.ok(m);
    }

    /** 提交班结：系统数由服务端现算后落快照，前端传来的只有实点金额与说明 */
    @PostMapping("/api/finance/shift-close")
    @PreAuthorize("hasAnyRole('ADMIN','CASHIER')")
    public R<Map<String, Object>> shiftCloseSubmit(@RequestBody Map<String, Object> body,
                                                   Authentication auth) {
        String d = normalizeDate(body.get("date") == null ? null : String.valueOf(body.get("date")));
        Long cashierId = currentUser.idOf(auth);
        if (cashierId == null) {
            return R.fail(5024, "无法识别当前登录收费员账号，请重新登录后再试");
        }
        Object raw = body.get("declaredCash");
        if (raw == null || String.valueOf(raw).isBlank()) {
            return R.fail(5021, "请填写实际点钞金额");
        }
        BigDecimal declared;
        try {
            declared = new BigDecimal(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return R.fail(5021, "实际点钞金额必须是数字");
        }
        if (declared.signum() < 0) {
            return R.fail(5021, "实际点钞金额不得为负");
        }
        var sys = systemTotals(d, cashierId);
        BigDecimal net = (BigDecimal) sys.get("sysNet");
        BigDecimal diff = declared.subtract(net);   // 长款为正、短款为负
        String note = body.get("note") == null ? null : String.valueOf(body.get("note"));
        // 重复提交靠唯一约束 + on conflict do nothing 判定：先 select 再 insert 的写法
        // 在两个窗口同时点"提交"时会双双通过检查（与 1.2.9 门诊退费同一类坑）
        int n = jdbc.update("""
                insert into fin_cashier_shift(cashier_id, shift_date, sys_paid, sys_refund, sys_net,
                                              declared_cash, diff, status, note, submitted_at)
                values (?, ?::date, ?, ?, ?, ?, ?, 'SUBMITTED', ?, now())
                on conflict (cashier_id, shift_date) do nothing
                """, cashierId, d, sys.get("sysPaid"), sys.get("sysRefund"), net, declared, diff, note);
        if (n == 0) {
            return R.fail(5020, "您 " + d + " 的班结缴款单已提交，不能重复提交（如需更正请联系财务）");
        }
        var m = new LinkedHashMap<String, Object>(sys);
        m.put("id", jdbc.queryForObject(
                "select id from fin_cashier_shift where cashier_id = ? and shift_date = ?::date",
                Long.class, cashierId, d));
        m.put("shiftDate", d);
        m.put("declaredCash", declared);
        m.put("diff", diff);
        m.put("status", "SUBMITTED");
        return R.ok(m);
    }

    /**
     * 财务确认（仅 ADMIN——类级注解已限定，此处不再叠加）。
     * 条件更新 `status = 'SUBMITTED'` 防两位财务同时点确认时重复写确认痕迹。
     */
    @PutMapping("/api/finance/shift-close/{id}/confirm")
    public R<Void> shiftCloseConfirm(@PathVariable Long id, Authentication auth) {
        int n = jdbc.update("""
                update fin_cashier_shift
                   set status = 'CONFIRMED', confirmed_by = ?, confirmed_at = now()
                 where id = ? and status = 'SUBMITTED'
                """, currentUser.idOf(auth), id);
        if (n == 0) {
            Integer exists = jdbc.queryForObject(
                    "select count(*) from fin_cashier_shift where id = ?", Integer.class, id);
            return exists != null && exists > 0
                    ? R.fail(5023, "该班结单不是待确认状态（可能已被确认），请刷新后查看")
                    : R.fail(5022, "班结单不存在");
        }
        return R.ok();
    }

    /**
     * 班结列表。ADMIN 看全院，其余角色（收费员）**强制只看自己**：
     * 过滤条件由服务端按登录态钉死，不读任何来自请求的 cashierId——
     * 前端隐藏按钮不是权限边界，越权取数只能在这里挡。
     */
    @GetMapping("/api/finance/shift-close")
    @PreAuthorize("hasAnyRole('ADMIN','CASHIER')")
    public R<List<Map<String, Object>>> shiftCloseList(@RequestParam(required = false) String date,
                                                       @RequestParam(required = false) String status,
                                                       Authentication auth) {
        String d = date == null || date.isBlank() ? null : java.time.LocalDate.parse(date).toString();
        String st = status == null || status.isBlank() ? null : status;
        Long scope = isAdmin(auth) ? null : currentUser.idOf(auth);
        if (!isAdmin(auth) && scope == null) {
            return R.fail(5024, "无法识别当前登录收费员账号，请重新登录后再试");
        }
        return R.ok(jdbc.queryForList("""
                select s.id, s.cashier_id, s.shift_date, s.sys_paid, s.sys_refund, s.sys_net,
                       s.declared_cash, s.diff, s.status, s.note, s.submitted_at, s.confirmed_at,
                       coalesce(u.real_name, u.username, '未记录')  as cashier,
                       coalesce(cu.real_name, cu.username)          as confirmed_by_name
                  from fin_cashier_shift s
                  left join sys_user u  on u.id = s.cashier_id
                  left join sys_user cu on cu.id = s.confirmed_by
                 where (?::date is null    or s.shift_date = ?::date)
                   and (?::varchar is null or s.status     = ?::varchar)
                   and (?::bigint is null  or s.cashier_id = ?::bigint)
                 order by s.shift_date desc, s.id desc
                """, d, d, st, st, scope, scope));
    }
}
