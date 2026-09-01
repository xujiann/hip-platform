package cn.hip.inpatient.service;

import cn.hip.inpatient.service.InpatientService.InpException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * v41 住院欠费挂账台账：欠费出院后的追缴闭环（挂账 → 催缴 → 补缴 → 结清/核销）。
 *
 * <p><b>与押金口径的物理隔离（本特性的核心约束）</b>：补缴是「收欠款」而非「补押金」。
 * 全部补缴只落 inp_arrears_payment，绝不写 inp_deposit、绝不改 inp_settlement。理由：
 * <ul>
 *   <li>写 inp_deposit 会让 /account 与 interimSettle 的余额口径（押金合计 - 已发生费用）凭空变正，
 *       欠费在账面上"自愈"，而实际欠款仍在；</li>
 *   <li>改 inp_settlement 会污染已出院患者的结算历史快照——结算是既成事实，
 *       欠费台账是它的下游派生账，只能引用、不能回写。</li>
 * </ul>
 * 因此本服务对住院资金主账（押金 / 结算 / 医嘱计费）是严格只读的。
 *
 * <p><b>幂等</b>：inp_arrears.admission_id 唯一。出院召回（cancelSettlement）后重新结算时，
 * syncOnDischarge 走 on conflict 更新金额与结算单引用并重算状态，不会重复挂账；
 * 若重结算后已不欠费（召回期间补交了押金），则撤掉这条尚无任何人工痕迹的自动挂账。
 */
@Service
@RequiredArgsConstructor
public class ArrearsService {

    private final JdbcTemplate jdbc;

    private static final List<String> DUNNING_METHODS = List.of("PHONE", "SMS", "VISIT", "LETTER");

    /**
     * 出院结算后同步欠费挂账（discharge 唯一调用点，只追加不改结算时序）。
     *
     * @param balance 结算余额（押金 - 费用）；&lt; 0 即欠费，欠费额 = -balance
     */
    @Transactional
    public void syncOnDischarge(Long admissionId, Long settleId, BigDecimal balance) {
        if (balance != null && balance.signum() < 0) {
            // 幂等挂账：召回重结算时更新金额与结算单引用，并按已补缴额重算状态。
            // 已核销的保持核销（人工决定不因系统重算而被推翻）。
            jdbc.update("""
                    insert into inp_arrears (admission_id, settle_id, amount, status)
                    values (?, ?, ?, 'OPEN')
                    on conflict (admission_id) do update set
                        settle_id  = excluded.settle_id,
                        amount     = excluded.amount,
                        updated_at = now(),
                        status = case
                            when inp_arrears.status = 'WRITTEN_OFF' then 'WRITTEN_OFF'
                            when coalesce((select sum(p.amount) from inp_arrears_payment p
                                           where p.arrears_id = inp_arrears.id), 0) >= excluded.amount then 'CLEARED'
                            when coalesce((select sum(p.amount) from inp_arrears_payment p
                                           where p.arrears_id = inp_arrears.id), 0) > 0 then 'PARTIAL'
                            else 'OPEN' end
                    """, admissionId, settleId, balance.negate());
            return;
        }
        // 重结算后已不欠费：撤掉这条纯自动生成、尚无任何人工痕迹（无补缴、无催缴）的挂账，
        // 否则台账会长期挂一条实际不存在的欠款。有人工痕迹的一律保留，交人工核销处理。
        jdbc.update("""
                delete from inp_arrears a
                where a.admission_id = ?
                  and not exists (select 1 from inp_arrears_payment p where p.arrears_id = a.id)
                  and not exists (select 1 from inp_arrears_dunning d where d.arrears_id = a.id)
                """, admissionId);
    }

    /** 台账行视图：欠费额/已补/剩余/催缴次数一次算清，清单与详情共用同一口径 */
    private static final String LEDGER_SQL = """
            select ar.id, ar.admission_id, a.admission_no, a.status as admission_status,
                   p.patient_no, p.name as patient_name,
                   d.name as dept_name, s.settle_no,
                   ar.amount,
                   coalesce(pay.paid, 0) as paid_amount,
                   ar.amount - coalesce(pay.paid, 0) as remain_amount,
                   ar.status, coalesce(dun.cnt, 0) as dunning_count,
                   ar.write_off_reason, ar.written_off_at, ar.created_at
            from inp_arrears ar
            join inp_admission a on a.id = ar.admission_id
            join empi_patient p on p.id = a.patient_id
            left join sys_dept d on d.id = a.dept_id
            left join inp_settlement s on s.id = ar.settle_id
            left join (select arrears_id, sum(amount) paid from inp_arrears_payment group by arrears_id)
                   pay on pay.arrears_id = ar.id
            left join (select arrears_id, count(*) cnt from inp_arrears_dunning group by arrears_id)
                   dun on dun.arrears_id = ar.id
            """;

    /** 欠费台账清单（status 为 null/空则全量）：住院号/患者/科室/欠费额/已补/剩余/状态/催缴次数 */
    public List<Map<String, Object>> list(String status) {
        if (status == null || status.isBlank()) {
            return jdbc.queryForList(LEDGER_SQL + " order by ar.id desc");
        }
        return jdbc.queryForList(LEDGER_SQL + " where ar.status = ? order by ar.id desc", status);
    }

    /** 单条欠费的补缴与催缴明细（弹窗回看） */
    public Map<String, Object> detail(Long arrearsId) {
        var heads = jdbc.queryForList(LEDGER_SQL + " where ar.id = ?", arrearsId);
        if (heads.isEmpty()) {
            throw new InpException(9035, "欠费记录不存在");
        }
        return Map.of(
                "arrears", heads.get(0),
                "payments", jdbc.queryForList("""
                        select p.id, p.amount, p.pay_method, p.paid_at, u.real_name as operator_name
                        from inp_arrears_payment p
                        left join sys_user u on u.id = p.operator_id
                        where p.arrears_id = ? order by p.id
                        """, arrearsId),
                "dunnings", jdbc.queryForList("""
                        select d.id, d.method, d.note, d.dunned_at, u.real_name as operator_name
                        from inp_arrears_dunning d
                        left join sys_user u on u.id = d.operator_id
                        where d.arrears_id = ? order by d.id desc
                        """, arrearsId));
    }

    /**
     * 补缴：累计足额自动置 CLEARED，超额拒绝、非正数拒绝。
     *
     * <p>只写 inp_arrears_payment 与 inp_arrears.status——押金表与结算行零触碰（见类注释）。
     * 行锁（select ... for update）序列化并发双击/双窗口，杜绝两笔并发补缴各自看到旧的"剩余"而合计超额。
     */
    @Transactional
    public Map<String, Object> pay(Long arrearsId, BigDecimal amount, String payMethod, Long operatorId) {
        if (amount == null || amount.signum() <= 0) {
            throw new InpException(9036, "补缴金额须大于 0");
        }
        Map<String, Object> ar = lockForUpdate(arrearsId);
        String status = (String) ar.get("status");
        if ("CLEARED".equals(status) || "WRITTEN_OFF".equals(status)) {
            throw new InpException(9038, "该欠费已结清或已核销，不能再补缴");
        }
        BigDecimal total = (BigDecimal) ar.get("amount");
        BigDecimal paid = paidOf(arrearsId);
        BigDecimal remain = total.subtract(paid);
        if (amount.compareTo(remain) > 0) {
            throw new InpException(9037, "补缴金额超过剩余欠费 " + remain.toPlainString() + " 元");
        }
        jdbc.update("insert into inp_arrears_payment (arrears_id, amount, pay_method, operator_id) values (?,?,?,?)",
                arrearsId, amount, payMethod == null || payMethod.isBlank() ? "CASH" : payMethod, operatorId);
        BigDecimal newPaid = paid.add(amount);
        String newStatus = newPaid.compareTo(total) >= 0 ? "CLEARED" : "PARTIAL";
        jdbc.update("update inp_arrears set status = ?, updated_at = now() where id = ?", newStatus, arrearsId);
        return Map.of("arrearsId", arrearsId, "amount", total, "paidAmount", newPaid,
                "remainAmount", total.subtract(newPaid), "status", newStatus);
    }

    /** 催缴登记（电话/短信/上门/书面）：已结清或已核销的不再催缴 */
    @Transactional
    public Map<String, Object> dun(Long arrearsId, String method, String note, Long operatorId) {
        Map<String, Object> ar = lockForUpdate(arrearsId);
        String status = (String) ar.get("status");
        if ("CLEARED".equals(status) || "WRITTEN_OFF".equals(status)) {
            throw new InpException(9038, "该欠费已结清或已核销，无需催缴");
        }
        if (method == null || !DUNNING_METHODS.contains(method)) {
            throw new InpException(9039, "催缴方式非法，须为 PHONE/SMS/VISIT/LETTER 之一");
        }
        jdbc.update("insert into inp_arrears_dunning (arrears_id, method, note, operator_id) values (?,?,?,?)",
                arrearsId, method, note, operatorId);
        jdbc.update("update inp_arrears set updated_at = now() where id = ?", arrearsId);
        Integer cnt = jdbc.queryForObject(
                "select count(*) from inp_arrears_dunning where arrears_id = ?", Integer.class, arrearsId);
        return Map.of("arrearsId", arrearsId, "dunningCount", cnt == null ? 0 : cnt);
    }

    /**
     * 核销（坏账）：仅 ADMIN（端点方法级 @PreAuthorize 把关）。原因必填——核销是把应收变成损失，
     * 法定与审计都要求可追溯到人与理由。已核销/已结清不可再核销。
     */
    @Transactional
    public Map<String, Object> writeOff(Long arrearsId, String reason, Long operatorId) {
        if (reason == null || reason.isBlank()) {
            throw new InpException(9040, "核销原因必填");
        }
        Map<String, Object> ar = lockForUpdate(arrearsId);
        String status = (String) ar.get("status");
        if ("CLEARED".equals(status) || "WRITTEN_OFF".equals(status)) {
            throw new InpException(9038, "该欠费已结清或已核销，不能核销");
        }
        jdbc.update("""
                update inp_arrears
                set status = 'WRITTEN_OFF', write_off_reason = ?, write_off_by = ?,
                    written_off_at = now(), updated_at = now()
                where id = ?
                """, reason, operatorId, arrearsId);
        return Map.of("arrearsId", arrearsId, "status", "WRITTEN_OFF");
    }

    /** 取欠费行并加行锁（并发补缴/核销序列化）；不存在即 9035 */
    private Map<String, Object> lockForUpdate(Long arrearsId) {
        var rows = jdbc.queryForList(
                "select id, amount, status from inp_arrears where id = ? for update", arrearsId);
        if (rows.isEmpty()) {
            throw new InpException(9035, "欠费记录不存在");
        }
        return rows.get(0);
    }

    private BigDecimal paidOf(Long arrearsId) {
        BigDecimal paid = jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from inp_arrears_payment where arrears_id = ?",
                BigDecimal.class, arrearsId);
        return paid == null ? BigDecimal.ZERO : paid;
    }
}
