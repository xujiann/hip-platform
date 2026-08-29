package cn.hip.outpatient.service;

import cn.hip.outpatient.entity.OutpCharge;
import cn.hip.outpatient.repository.OutpChargeRepository;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.core.service.ConfigReader;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 退费审批链（v30）：大额退费须先审批后执行——常见舞弊/差错点。
 *
 * <p>设计为**不侵入 ChargeService.refund 的抢占逻辑**：退费入口先过 {@link #assertApproved}，
 * 未超阈值直接放行（旧行为），超阈值则要求存在一张 APPROVED 且未用过的审批单。
 * 阈值走 sys_config `refund_approval_threshold`，0=不设闸。用 JdbcTemplate 而非新增
 * 实体依赖，保持 ChargeService 依赖面不变。
 */
@Service
@RequiredArgsConstructor
public class RefundApprovalService {

    private final JdbcTemplate jdbc;
    private final OutpChargeRepository chargeRepository;
    private final ConfigReader configReader;

    /** 该结算单是否需要审批（金额 ≥ 阈值且阈值 > 0） */
    public boolean needsApproval(BigDecimal amount) {
        // 阈值脏值容错（第七轮审阅 P3-2）：配置被写成非数字时不应让退费全线 500，回落默认 500
        BigDecimal threshold;
        try {
            threshold = new BigDecimal(configReader.get("refund_approval_threshold", "500").trim());
        } catch (NumberFormatException e) {
            threshold = new BigDecimal("500");
        }
        return threshold.signum() > 0 && amount.compareTo(threshold) >= 0;
    }

    /**
     * 退费执行前的审批闸。超阈值而无 APPROVED 审批单即拒绝，引导走申请流程。
     * 放行时把审批单置 EXECUTED，防同一审批被复用于二次退费。
     */
    @Transactional
    public void assertApproved(OutpCharge charge) {
        if (!needsApproval(charge.getTotalAmount())) {
            return;   // 未超阈值：维持旧行为直接退
        }
        // 取该单最近一张 APPROVED 审批（未被 EXECUTED 消费）
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id from outp_refund_approval where charge_id = ? and status = 'APPROVED' "
                        + "order by approved_at desc limit 1", charge.getId());
        if (rows.isEmpty()) {
            throw new BizException(5011,
                    "退费金额 %s 元达到审批阈值，请先提交退费审批并由授权人通过后再退费"
                            .formatted(charge.getTotalAmount().toPlainString()));
        }
        // 消费该审批单：置 EXECUTED，条件更新防并发复用
        Long approvalId = ((Number) rows.get(0).get("id")).longValue();
        int n = jdbc.update(
                "update outp_refund_approval set status = 'EXECUTED' where id = ? and status = 'APPROVED'",
                approvalId);
        if (n == 0) {
            throw new BizException(5011, "退费审批已被使用，请重新提交审批");
        }
    }

    /** 收费员提交退费审批申请 */
    @Transactional
    public Long apply(Long chargeId, String reason, Long applicantId) {
        OutpCharge charge = chargeRepository.findById(chargeId)
                .orElseThrow(() -> new BizException(5002, "结算单不存在"));
        if (!"PAID".equals(charge.getStatus())) {
            throw new BizException(5003, "该结算单已退费或状态异常，无需审批");
        }
        if (!needsApproval(charge.getTotalAmount())) {
            throw new BizException(5012, "该金额未达审批阈值，可直接退费无需申请");
        }
        // 唯一部分索引挡重复 PENDING——这里先友好提示，撞索引再兜底
        Integer pending = jdbc.queryForObject(
                "select count(*) from outp_refund_approval where charge_id = ? and status = 'PENDING'",
                Integer.class, chargeId);
        if (pending != null && pending > 0) {
            throw new BizException(5013, "该结算单已有待审批的退费申请，请勿重复提交");
        }
        try {
            jdbc.update("""
                    insert into outp_refund_approval(charge_id, charge_no, amount, reason, applied_by)
                    values (?,?,?,?,?)
                    """, chargeId, charge.getChargeNo(), charge.getTotalAmount(), reason, applicantId);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 并发两个 apply 同时越过上面的 count 检查时，第二条撞唯一部分索引 uq_refund_approval_pending。
            // 转成可读 5013 而非裸 500（第七轮审阅 P3-1）
            throw new BizException(5013, "该结算单已有待审批的退费申请，请勿重复提交");
        }
        return jdbc.queryForObject(
                "select id from outp_refund_approval where charge_id = ? and status = 'PENDING'",
                Long.class, chargeId);
    }

    /** 授权人审批：通过/驳回。职责分离——审批人不得是申请人（第七轮审阅 P2-4） */
    @Transactional
    public void decide(Long approvalId, boolean approved, String note, Long approverId) {
        // 大额退费审批的立项目的就是防舞弊/职责分离：自己申请自己批等于没有审批。
        // approverId 为 null（测试或系统调用）时不设防，仅拦真实的自申自批
        if (approverId != null) {
            Long applicant = jdbc.queryForList(
                    "select applied_by from outp_refund_approval where id = ?", Long.class, approvalId)
                    .stream().findFirst().orElse(null);
            if (approverId.equals(applicant)) {
                throw new BizException(5015, "退费审批须由他人复核，不能审批自己提交的申请");
            }
        }
        int n = jdbc.update("""
                update outp_refund_approval
                   set status = ?, approved_by = ?, approved_at = now(), approve_note = ?
                 where id = ? and status = 'PENDING'
                """, approved ? "APPROVED" : "REJECTED", approverId, note, approvalId);
        if (n == 0) {
            throw new BizException(5014, "审批单不存在或已被处理");
        }
    }

    /** 待审批列表（授权人视角） */
    public List<Map<String, Object>> pendingList() {
        return jdbc.queryForList("""
                select a.id, a.charge_no, a.amount, a.reason, a.applied_at,
                       u.real_name as applied_by_name
                  from outp_refund_approval a
                  left join sys_user u on u.id = a.applied_by
                 where a.status = 'PENDING'
                 order by a.applied_at
                """);
    }
}
