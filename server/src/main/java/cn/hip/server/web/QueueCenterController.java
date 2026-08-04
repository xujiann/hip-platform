package cn.hip.server.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 二十三期：叫号扩展——候诊队列引擎泛化到药房取药/检查/检验三场景 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/queue-center")
public class QueueCenterController {

    private final JdbcTemplate jdbc;

    private static final Set<String> TYPES = Set.of("PHARMACY", "EXAM", "LAB");

    /** 队列大屏：等待中 + 今日已叫 */
    @GetMapping("/{type}")
    public R<Map<String, Object>> queue(@PathVariable String type) {
        if (!TYPES.contains(type)) return R.fail(4520, "未知队列类型");
        var m = new LinkedHashMap<String, Object>();
        m.put("waiting", waiting(type));
        m.put("called", jdbc.queryForList("""
                select biz_key, patient_name, called_at from queue_call_log
                where biz_type = ? and called_at::date = current_date
                order by id desc limit 10
                """, type));
        return R.ok(m);
    }

    /** 叫下一号 */
    @PostMapping("/{type}/call-next")
    @Transactional
    public R<Map<String, Object>> callNext(@PathVariable String type) {
        if (!TYPES.contains(type)) return R.fail(4520, "未知队列类型");
        List<Map<String, Object>> waiting = waiting(type);
        if (waiting.isEmpty()) return R.fail(4521, "当前无等待队列");
        var next = waiting.get(0);
        jdbc.update("insert into queue_call_log(biz_type, biz_key, patient_name) values (?,?,?)",
                type, String.valueOf(next.get("biz_key")), next.get("patient_name"));
        if (!"PHARMACY".equals(type)) {
            jdbc.update("update med_appointment set status = 'CALLED' where id = ?::bigint and status = 'BOOKED'",
                    String.valueOf(next.get("biz_key")));
        }
        return R.ok(next);
    }

    private List<Map<String, Object>> waiting(String type) {
        if ("PHARMACY".equals(type)) {
            // 待发药处方（按处方号聚合），今日已叫过的不再排队
            return jdbc.queryForList("""
                    select o.group_no as biz_key, min(p.name) as patient_name,
                           string_agg(o.item_name, '、') as detail
                    from outp_order o
                    join outp_registration r on r.id = o.registration_id
                    join empi_patient p on p.id = r.patient_id
                    where o.order_type = 'DRUG' and o.status = 'CHARGED'
                      and not exists (select 1 from queue_call_log q
                                      where q.biz_type = 'PHARMACY' and q.biz_key = o.group_no
                                        and q.called_at::date = current_date)
                    group by o.group_no order by o.group_no limit 50
                    """);
        }
        // EXAM/LAB：已预约今日队列，未叫号
        return jdbc.queryForList("""
                select a.id::varchar as biz_key, p.name as patient_name,
                       o.item_name || '（' || a.period || ' 第' || a.seq_no || '号）' as detail
                from med_appointment a
                join outp_order o on o.id = a.order_id
                join outp_registration r on r.id = o.registration_id
                join empi_patient p on p.id = r.patient_id
                where o.order_type = ? and a.status = 'BOOKED' and a.slot_date = current_date
                order by a.period, a.seq_no limit 50
                """, type);
    }
}
