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

    /**
     * 报文预取（1.1.7）：把一次对账涉及的全部单号的 YB 报文一次取回、按单号建字典——
     * 原实现每单 2 次点查，日结算 3000 笔 = 单次对账 6000+ 条 SQL（同步接口，用户点一下等数秒）。
     */
    private class MsgLookup {
        private final Map<String, List<String>> payloadsByRef = new java.util.HashMap<>();

        MsgLookup(java.util.Collection<String> refNos) {
            if (refNos.isEmpty()) return;
            var named = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
            for (var row : named.queryForList("""
                    select ref_no, payload from int_message_log
                    where channel = 'YB' and status = 'OK' and ref_no in (:nos)
                    order by id desc
                    """, Map.of("nos", refNos))) {
                payloadsByRef.computeIfAbsent((String) row.get("ref_no"), k -> new ArrayList<>())
                        .add((String) row.get("payload"));
            }
        }

        ChannelMsg get(String refNo, String apiKeyword) {
            var payloads = payloadsByRef.getOrDefault(refNo, List.of());
            for (String p : payloads) {
                if (p.contains(apiKeyword)) {
                    Matcher m = AMOUNT.matcher(p);
                    return new ChannelMsg(true, m.find() ? new BigDecimal(m.group(1)) : null);
                }
            }
            return new ChannelMsg(false, null);
        }
    }

    public List<Map<String, Object>> reconRows(String date) {
        List<Map<String, Object>> out = new ArrayList<>();
        // 四段单据清单先行取回，合并单号一次预取全部报文（1.1.7：原每单 2 次点查）
        var outpCharges = jdbc.queryForList("""
                select charge_no, total_amount, status from outp_charge
                where pay_method = 'YB'
                  and created_at >= ?::date and created_at < ?::date + interval '1 day'
                order by id
                """, date, date);
        var outpRefunds = jdbc.queryForList("""
                select charge_no, total_amount from outp_charge
                where pay_method = 'YB' and status = 'REFUNDED'
                  and refunded_at >= ?::date and refunded_at < ?::date + interval '1 day'
                  and (created_at < ?::date or created_at >= ?::date + interval '1 day')
                order by id
                """, date, date, date, date);
        var inpSettles = jdbc.queryForList("""
                select settle_no, total_amount, status from inp_settlement
                -- 显式收 FINAL（第七轮审阅 P3-C）：此前安全仅隐式依赖 interimSettle 拒 YB(9032)——
                -- 一旦放开中间结算走医保通道，对账会把 INTERIM 当出院 YB 单去比报文而满屏误报。
                -- 本地自证：医保对账只认出院结算
                where pay_method = 'YB' and settle_type = 'FINAL'
                  and created_at >= ?::date and created_at < ?::date + interval '1 day'
                order by id
                """, date, date);
        var inpCancels = jdbc.queryForList("""
                select settle_no, total_amount from inp_settlement
                where pay_method = 'YB' and status = 'CANCELLED' and settle_type = 'FINAL'
                  and refunded_at >= ?::date and refunded_at < ?::date + interval '1 day'
                  and (created_at < ?::date or created_at >= ?::date + interval '1 day')
                order by id
                """, date, date, date, date);
        var allNos = new java.util.LinkedHashSet<String>();
        outpCharges.forEach(r -> allNos.add((String) r.get("charge_no")));
        outpRefunds.forEach(r -> allNos.add((String) r.get("charge_no")));
        inpSettles.forEach(r -> allNos.add((String) r.get("settle_no")));
        inpCancels.forEach(r -> allNos.add((String) r.get("settle_no")));
        MsgLookup msgs = new MsgLookup(allNos);

        // 门诊：YB 结算单 ←→ 通道 settle/refund 报文
        for (var c : outpCharges) {
            String no = (String) c.get("charge_no");
            BigDecimal amount = (BigDecimal) c.get("total_amount");
            ChannelMsg settle = msgs.get(no, "settle");
            ChannelMsg refund = msgs.get(no, "refund");
            boolean refunded = "REFUNDED".equals(c.get("status"));
            boolean amountOk = settle.exists() && (settle.amount() == null
                    || settle.amount().compareTo(amount) == 0);
            // PAID 单却存在冲正报文=渠道已冲、本地未退（B-3 时序事故的产物），必须标异常
            boolean orphanRefund = !refunded && refund.exists();
            boolean consistent = (refunded ? settle.exists() && refund.exists() : settle.exists())
                    && amountOk && !orphanRefund;
            var row = reconRow("OUTP", no, amount, (String) c.get("status"),
                    settle.exists(), refund.exists(), consistent);
            row.put("channel_amount", settle.amount());
            row.put("amount_match", amountOk);
            if (settle.exists() && !amountOk) {
                row.put("note", "金额不符：业务 %s / 通道 %s".formatted(amount, settle.amount()));
            } else if (orphanRefund) {
                row.put("note", "本地未退费但通道存在冲正报文（疑似渠道已冲、本地未退）");
            }
            out.add(row);
        }
        // 退费侧窗口（1.1.6 B-4）：D1 结算 D5 退费的单，created_at 窗口永远不会复检它——
        // "本地已退、医保未冲"就此漏网。按退费日再扫一遍，只核退费动作对应的冲正报文。
        for (var c : outpRefunds) {
            String no = (String) c.get("charge_no");
            ChannelMsg refund = msgs.get(no, "refund");
            var row = reconRow("OUTP_REFUND", no, c.get("total_amount"), "REFUNDED",
                    true, refund.exists(), refund.exists());
            if (!refund.exists()) {
                row.put("note", "当日退费但通道无冲正报文（本地已退、医保未冲）");
            }
            out.add(row);
        }
        // 住院：YB 出院结算 ←→ 通道 settle 报文；已冲销单要求成对的 settle+refund
        for (var s : inpSettles) {
            String no = (String) s.get("settle_no");
            BigDecimal amount = (BigDecimal) s.get("total_amount");
            ChannelMsg settle = msgs.get(no, "settle");
            ChannelMsg refund = msgs.get(no, "refund");
            boolean cancelled = "CANCELLED".equals(s.get("status"));
            boolean amountOk = settle.exists() && (settle.amount() == null
                    || settle.amount().compareTo(amount) == 0);
            // 与门诊同口径（1.2.3 五轮 P1-1）：PAID 单却存在冲正报文=渠道已冲、本地未冲销——
            // cancelSettlement 渠道成功后提交阶段失败会留下这个形态，此前住院侧判"一致"永不现形
            boolean orphanRefund = !cancelled && refund.exists();
            boolean consistent = (cancelled ? settle.exists() && refund.exists() : settle.exists())
                    && amountOk && !orphanRefund;
            var row = reconRow("INP", no, amount, (String) s.get("status"),
                    settle.exists(), refund.exists(), consistent);
            row.put("channel_amount", settle.amount());
            row.put("amount_match", amountOk);
            if (settle.exists() && !amountOk) {
                row.put("note", "金额不符：业务 %s / 通道 %s".formatted(amount, settle.amount()));
            } else if (orphanRefund) {
                row.put("note", "本地未冲销但通道存在冲正报文（疑似渠道已冲、本地未冲销）");
            }
            out.add(row);
        }
        // 住院退费侧窗口（B-4）：异日冲销的结算单按冲销日复检冲正报文
        for (var s : inpCancels) {
            String no = (String) s.get("settle_no");
            ChannelMsg refund = msgs.get(no, "refund");
            var row = reconRow("INP_REFUND", no, s.get("total_amount"), "CANCELLED",
                    true, refund.exists(), refund.exists());
            if (!refund.exists()) {
                row.put("note", "当日冲销但通道无冲正报文（本地已冲销、医保未冲正）");
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
