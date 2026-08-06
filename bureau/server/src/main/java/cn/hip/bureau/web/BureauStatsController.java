package cn.hip.bureau.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 统计分析与督办看板（执法公示与全过程监督支撑） */
@RestController
@RequestMapping("/api/bureau/stats")
@RequiredArgsConstructor
public class BureauStatsController {

    private final JdbcTemplate jdbc;

    /** 总览：案件分状态计数、罚没/追回金额合计、线索转化 */
    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("clueTotal", one("select count(*) from case_clue"));
        m.put("cluePending", one("select count(*) from case_clue where status = 'PENDING'"));
        m.put("clueFiled", one("select count(*) from case_clue where status = 'FILED'"));
        m.put("caseTotal", one("select count(*) from case_file"));
        m.put("caseByStatus", jdbc.queryForList(
                "select status, count(*) as cnt from case_file group by status order by status"));
        m.put("fineDecided", one("select coalesce(sum(fine_amount),0) from case_decision"));
        m.put("recoupDecided", one("select coalesce(sum(recoup_amount),0) from case_decision"));
        m.put("confiscateDecided", one("select coalesce(sum(confiscate_amount),0) from case_decision"));
        m.put("fineCollected", one("select coalesce(sum(amount),0) from case_execution where kind in ('FINE','LATE_FEE','CONFISCATE')"));
        m.put("recoupCollected", one("select coalesce(sum(amount),0) from case_execution where kind = 'RECOUP'"));
        m.put("byPartyType", jdbc.queryForList(
                "select party_type, count(*) as cnt from case_file group by party_type order by cnt desc"));
        m.put("byCause", jdbc.queryForList("""
                select cc.category, count(*) as cnt from case_file cf
                join case_cause cc on cc.id = cf.cause_id group by cc.category order by cnt desc limit 10"""));
        m.put("monthlyFiled", jdbc.queryForList("""
                select to_char(filed_at, 'YYYY-MM') as ym, count(*) as cnt from case_file
                where filed_at >= current_date - interval '12 months' group by 1 order by 1"""));
        return R.ok(m);
    }

    /** 督办看板：各环节超期/临期预警（第14/40/45/59条时限） */
    @GetMapping("/supervision")
    public R<Map<String, Object>> supervision() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        // 线索核查超期（第14条）
        m.put("clueOverdue", jdbc.queryForList("""
                select id, clue_no, suspect_name, received_at, deadline_at, extended
                from case_clue where status = 'PENDING' and deadline_at < current_date order by deadline_at"""));
        // 办案期限：临期(10日内)与超期（第45条，含扣除期间顺延）
        m.put("caseNearDeadline", jdbc.queryForList("""
                select cf.id, cf.case_no, cf.name, cf.status, cf.filed_at,
                       cf.deadline_at + coalesce((select sum(e.end_at - e.start_at)::int from case_period_exclusion e
                                                  where e.case_id = cf.id and e.end_at is not null), 0) as effective_deadline
                from case_file cf
                where cf.status in ('INVESTIGATING','REPORTED','NOTIFIED')
                  and cf.deadline_at + coalesce((select sum(e.end_at - e.start_at)::int from case_period_exclusion e
                                                 where e.case_id = cf.id and e.end_at is not null), 0)
                      < current_date + 10
                order by effective_deadline"""));
        // 法制审核超期（第40条：10个工作日）
        m.put("reviewOverdue", jdbc.queryForList("""
                select r.id, r.case_id, cf.case_no, r.submitted_at, r.deadline_at
                from case_review r join case_file cf on cf.id = r.case_id
                where r.reviewed_at is null and r.deadline_at < current_date order by r.deadline_at"""));
        // 送达超期（第59条：决定后7个工作日，此处按自然日9天近似预警）
        m.put("deliveryOverdue", jdbc.queryForList("""
                select id, case_no, name, decided_at from case_file
                where status = 'DECIDED' and decided_at < current_date - 9 order by decided_at"""));
        // 先行登记保存超期未处理（第26条：7个工作日）
        m.put("holdOverdue", jdbc.queryForList("""
                select ev.id, ev.case_id, cf.case_no, ev.name, ev.hold_expire_at
                from case_evidence ev join case_file cf on cf.id = ev.case_id
                where ev.register_hold = true and ev.hold_expire_at < current_date order by ev.hold_expire_at"""));
        // 封存到期（第31条）
        m.put("sealExpiring", jdbc.queryForList("""
                select ev.id, ev.case_id, cf.case_no, ev.name, ev.seal_expire_at, ev.seal_extended
                from case_evidence ev join case_file cf on cf.id = ev.case_id
                where ev.sealed = true and ev.seal_expire_at < current_date + 5 order by ev.seal_expire_at"""));
        return R.ok(m);
    }

    private Object one(String sql) {
        return jdbc.queryForObject(sql, Object.class);
    }
}
