package cn.hip.insurance.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 医保对账：业务账（门诊结算/住院结算的 YB 单）↔ 通道账（int_message_log 结算/冲正报文）逐单核对。
 * 匹配约定：channel='YB' + status='OK' + ref_no=单号 + payload 含 api 关键字（真实适配器实现须保持该约定，见 ADR-0002）。
 */
@Service
@RequiredArgsConstructor
public class InsuranceReconService {

    private final JdbcTemplate jdbc;

    public List<Map<String, Object>> reconRows(String date) {
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
        // 反向：通道有账、业务无账（医保侧多扣一笔）——单向遍历业务账永远发现不了
        for (var m : jdbc.queryForList("""
                select ref_no, payload from int_message_log
                where channel = 'YB' and status = 'OK' and created_at::date = ?::date
                  and payload like '%settle%' and ref_no is not null
                group by ref_no, payload order by ref_no
                """, date)) {
            String no = (String) m.get("ref_no");
            Integer inBiz = jdbc.queryForObject("""
                    select (select count(*) from outp_charge where charge_no = ?)
                         + (select count(*) from inp_settlement where settle_no = ?)
                    """, Integer.class, no, no);
            if (inBiz == null || inBiz == 0) {
                var row = reconRow("CHANNEL_ONLY", no, null, "—", true, false, false);
                row.put("note", "通道有结算报文但业务库无对应单据（疑似医保侧多扣）");
                out.add(row);
            }
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

    private boolean msgExists(String refNo, String apiKeyword) {
        Integer n = jdbc.queryForObject("""
                select count(*) from int_message_log
                where channel = 'YB' and status = 'OK' and ref_no = ? and payload like ?
                """, Integer.class, refNo, "%" + apiKeyword + "%");
        return n != null && n > 0;
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
