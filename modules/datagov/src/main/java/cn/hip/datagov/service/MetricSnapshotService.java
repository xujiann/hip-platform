package cn.hip.datagov.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 指标快照生成（v72 包 D 从 {@code DataGovController} 原样抽出，<b>计算口径一个字未改</b>）。
 *
 * <p>抽出来的唯一理由：定时任务不该去调控制器——本仓其余四个定时任务一律调服务层。
 * 控制器改为委托本服务，<b>对外端点与返回体的 {@code snapshotted} 键不变</b>
 * （两套 E2E 正在断言它，V72MetricSnapshotTest 已先行钉死）。
 *
 * <p><b>幂等</b>：按 (code, snap_date) 冲突覆盖，同日重复生成不追加行——
 * 这正是它能安全挂上定时任务的前提。
 */
@Service
@RequiredArgsConstructor
public class MetricSnapshotService {

    private final JdbcTemplate jdbc;

    /** 内置指标计算：builtin_key → SQL（自控制器原样迁入）。 */
    static final Map<String, String> BUILTIN = Map.of(
            "outp_reg_today", "select count(*) from outp_registration where visit_date = current_date and status <> 'CANCELLED'",
            "drug_ratio", """
                    select case when sum(amount) = 0 then 0 else
                        round(sum(amount) filter (where order_type = 'DRUG') / sum(amount) * 100, 2) end
                    from outp_order where status in ('CHARGED','DISPENSED','EXECUTED')
                    """,
            "bed_occupancy", "select round(count(*) filter (where status = 'OCCUPIED')::numeric / nullif(count(*), 0) * 100, 2) from inp_bed",
            "avg_outp_cost", "select coalesce(round(sum(total_amount) / nullif(count(distinct registration_id), 0), 2), 0) from outp_charge where status = 'PAID'",
            "in_hospital", "select count(*) from inp_admission where status = 'IN_HOSPITAL'",
            // 二十五期：公立医院评审指标集扩充
            "avg_los", """
                    select coalesce(round((avg(extract(epoch from (discharged_at - admit_at)) / 86400))::numeric, 1), 0)
                    from inp_admission where status = 'DISCHARGED'
                    """,
            "abx_rx_ratio", """
                    select coalesce(round(count(distinct o.group_no) filter (where d.abx_level >= 1)::numeric
                        / nullif(count(distinct o.group_no), 0) * 100, 2), 0)
                    from outp_order o join md_drug d on d.id = o.item_id
                    where o.order_type = 'DRUG' and o.status <> 'CANCELLED'
                    """,
            "emr_sign_ratio", """
                    select coalesce(round(count(*) filter (where signature is not null)::numeric
                        / nullif(count(*), 0) * 100, 2), 0) from outp_emr
                    """,
            "ris_verified_ratio", """
                    select coalesce(round(count(*) filter (where status = 'VERIFIED')::numeric
                        / nullif(count(*), 0) * 100, 2), 0) from ris_exam
                    """);

    /** 生成今日指标快照（幂等覆盖），返回落库的指标条数。 */
    @Transactional
    public int snapshotToday() {
        var defs = jdbc.queryForList("select code, builtin_key from dg_metric_def");
        int n = 0;
        for (var d : defs) {
            String sql = BUILTIN.get((String) d.get("builtin_key"));
            if (sql == null) continue;
            Double value = jdbc.queryForObject(sql, Double.class);
            jdbc.update("""
                    insert into dg_metric_snapshot(code, value, snap_date) values (?, ?, current_date)
                    on conflict (code, snap_date) do update set value = excluded.value, created_at = now()
                    """, d.get("code"), value == null ? 0 : value);
            n++;
        }
        return n;
    }
}
