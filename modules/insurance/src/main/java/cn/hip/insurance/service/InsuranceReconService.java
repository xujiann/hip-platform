package cn.hip.insurance.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 医保对账：业务账（门诊结算/住院结算的 YB 单）↔ 通道账（int_message_log 结算/冲正报文）逐单核对。
 * 匹配约定：channel='YB' + status='OK' + ref_no=单号 + payload 含 api 关键字与 `"amount":<金额>`
 * （真实适配器实现须保持该约定，见 ADR-0002——**留痕里必须有金额，对账才能比金额**）。
 *
 * <p>1.1.3（审阅 B-3）：consistent 不再只看报文存在性——金额不符是最常见的对账差异形态，
 * 原实现带着 amount 字段却从不比较，金额差异 100% 判为"一致"。
 */
@Service
@RequiredArgsConstructor
public class InsuranceReconService {

    private final JdbcTemplate jdbc;

    private static final Pattern AMOUNT = Pattern.compile("\"amount\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");

    /** 通道报文视角：是否存在 + 报文内金额（无金额字段时为 null，按"存在但金额不可比"处理） */
    private record ChannelMsg(boolean exists, BigDecimal amount) {}

    public List<Map<String, Object>> reconRows(String date) {
        List<Map<String, Object>> out = new ArrayList<>();
        // 门诊：YB 结算单 ←→ 通道 settle/refund 报文（半开区间：::date 谓词会废掉索引）
        for (var c : jdbc.queryForList("""
                select charge_no, total_amount, status from outp_charge
                where pay_method = 'YB'
                  and created_at >= ?::date and created_at < ?::date + interval '1 day'
                order by id
                """, date, date)) {
            String no = (String) c.get("charge_no");
            BigDecimal amount = (BigDecimal) c.get("total_amount");
            ChannelMsg settle = channelMsg(no, "settle");
            ChannelMsg refund = channelMsg(no, "refund");
            boolean refunded = "REFUNDED".equals(c.get("status"));
            boolean amountOk = settle.exists() && (settle.amount() == null
                    || settle.amount().compareTo(amount) == 0);
            boolean consistent = (refunded ? settle.exists() && refund.exists() : settle.exists())
                    && amountOk;
            var row = reconRow("OUTP", no, amount, (String) c.get("status"),
                    settle.exists(), refund.exists(), consistent);
            row.put("channel_amount", settle.amount());
            row.put("amount_match", amountOk);
            if (settle.exists() && !amountOk) {
                row.put("note", "金额不符：业务 %s / 通道 %s".formatted(amount, settle.amount()));
            }
            out.add(row);
        }
        // 住院：YB 出院结算 ←→ 通道 settle 报文；已冲销单要求成对的 settle+refund
        for (var s : jdbc.queryForList("""
                select settle_no, total_amount, status from inp_settlement
                where pay_method = 'YB'
                  and created_at >= ?::date and created_at < ?::date + interval '1 day'
                order by id
                """, date, date)) {
            String no = (String) s.get("settle_no");
            BigDecimal amount = (BigDecimal) s.get("total_amount");
            ChannelMsg settle = channelMsg(no, "settle");
            ChannelMsg refund = channelMsg(no, "refund");
            boolean cancelled = "CANCELLED".equals(s.get("status"));
            boolean amountOk = settle.exists() && (settle.amount() == null
                    || settle.amount().compareTo(amount) == 0);
            boolean consistent = (cancelled ? settle.exists() && refund.exists() : settle.exists())
                    && amountOk;
            var row = reconRow("INP", no, amount, (String) s.get("status"),
                    settle.exists(), refund.exists(), consistent);
            row.put("channel_amount", settle.amount());
            row.put("amount_match", amountOk);
            if (settle.exists() && !amountOk) {
                row.put("note", "金额不符：业务 %s / 通道 %s".formatted(amount, settle.amount()));
            }
            out.add(row);
        }
        // 反向：通道有账、业务无账（医保侧多扣一笔）——单向遍历业务账永远发现不了。
        // 存在性检查改一条反连接（B-12：原为每个 ref_no 两次子查询的 N+1）
        for (var m : jdbc.queryForList("""
                select t.ref_no from (
                    select distinct ref_no from int_message_log
                    where channel = 'YB' and status = 'OK'
                      and created_at >= ?::date and created_at < ?::date + interval '1 day'
                      and payload like '%settle%' and ref_no is not null
                ) t
                where not exists (select 1 from outp_charge c where c.charge_no = t.ref_no)
                  and not exists (select 1 from inp_settlement s where s.settle_no = t.ref_no)
                order by t.ref_no
                """, date, date)) {
            var row = reconRow("CHANNEL_ONLY", (String) m.get("ref_no"), null, "—", true, false, false);
            row.put("note", "通道有结算报文但业务库无对应单据（疑似医保侧多扣）");
            out.add(row);
        }
        return out;
    }

    /** 核对并保存批次留痕，返回 {total, matched, diff} */
    @Transactional
    public Map<String, Object> reconcileAndSave(String date) {
        List<Map<String, Object>> rows = reconRows(date);
        long matched = rows.stream().filter(r -> (Boolean) r.get("consistent")).count();
        String diffDetail = rows.stream().filter(r -> !(Boolean) r.get("consistent"))
                .map(r -> String.valueOf(r.get("charge_no"))).reduce((x, y) -> x + "," + y).orElse("");
        jdbc.update("""
                insert into yb_recon_batch(recon_date, total_cnt, matched_cnt, diff_cnt, detail)
                values (?::date,?,?,?,?)
                """, date, rows.size(), matched, rows.size() - matched, diffDetail);
        var m = new LinkedHashMap<String, Object>();
        m.put("total", rows.size());
        m.put("matched", matched);
        m.put("diff", rows.size() - matched);
        m.put("diffDetail", diffDetail);
        return m;
    }

    private ChannelMsg channelMsg(String refNo, String apiKeyword) {
        var payloads = jdbc.queryForList("""
                select payload from int_message_log
                where channel = 'YB' and status = 'OK' and ref_no = ? and payload like ?
                order by id desc limit 1
                """, String.class, refNo, "%" + apiKeyword + "%");
        if (payloads.isEmpty()) {
            return new ChannelMsg(false, null);
        }
        Matcher m = AMOUNT.matcher(payloads.get(0));
        return new ChannelMsg(true, m.find() ? new BigDecimal(m.group(1)) : null);
    }

    private LinkedHashMap<String, Object> reconRow(String bizType, String no, Object amount, String status,
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
}
