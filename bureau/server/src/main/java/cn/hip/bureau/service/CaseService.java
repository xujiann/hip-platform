package cn.hip.bureau.service;

import cn.hip.bureau.common.BizException;
import cn.hip.bureau.common.Workdays;
import cn.hip.bureau.entity.*;
import cn.hip.bureau.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 案件办理核心服务。法定规则硬校验（错误码 2xxx），依据《医疗保障行政处罚程序暂行规定》（国家医保局令第4号）：
 * 2001 案由主体不符 | 2002 执法人员不足两人（第16条） | 2003 简易程序限额（第48条）
 * 2004 调查未终结 | 2005 未经法制审核不得决定（第37条） | 2006 决定前未告知（第41条）
 * 2007 不得因陈述申辩加重处罚（第41条） | 2008 超办案期限（第45条） | 2009 延期超上限（第45条）
 * 2010 当场收缴限额（第52条） | 2011 结案条件不满足（第56条） | 2012 加处罚款超本金（第55条）
 */
@Service
@RequiredArgsConstructor
public class CaseService {

    private final CaseFileRepository caseRepository;
    private final CaseCauseRepository causeRepository;
    private final CaseReviewRepository reviewRepository;
    private final CaseNoticeRepository noticeRepository;
    private final CaseDecisionRepository decisionRepository;
    private final ClueService clueService;
    private final JdbcTemplate jdbc;

    // ---------- 立案 ----------

    public record OfficerReq(String name, String certNo, String duty) {}

    public record CaseCreateReq(Long clueId, Long causeId, String procedureType,
                                String partyName, String partyType, String partyCreditNo,
                                String partyAddress, String partyLegalRep, String partyContact,
                                String summary, BigDecimal amountInvolved,
                                List<OfficerReq> officers, String clueVerifyResult) {}

    @Transactional
    public CaseFile create(CaseCreateReq req, String username) {
        if (req.officers() == null || req.officers().size() < 2)
            throw new BizException(2002, "执法人员不得少于两人（第16条）");
        CaseCause cause = causeRepository.findById(req.causeId())
                .orElseThrow(() -> new BizException(2026, "案由不存在"));
        if (!cause.getSubjectType().equals(req.partyType()))
            throw new BizException(2001, "当事人类别与案由违法主体类别不一致（苏医保督〔2024〕1号）");

        CaseFile c = new CaseFile();
        LocalDate today = LocalDate.now();
        String prefix = cfg("case_no_prefix", "医保案") + "〔" + today.getYear() + "〕";
        c.setCaseNo(prefix + (caseRepository.countByCaseNoStartingWith(prefix) + 1) + "号");
        // 调查阶段案件名称：违法主体+涉嫌+案由+案
        c.setName(req.partyName() + "涉嫌" + cause.getCategory() + "案");
        c.setClueId(req.clueId());
        c.setCauseId(req.causeId());
        c.setProcedureType(req.procedureType() == null ? "NORMAL" : req.procedureType());
        c.setPartyName(req.partyName());
        c.setPartyType(req.partyType());
        c.setPartyCreditNo(req.partyCreditNo());
        c.setPartyAddress(req.partyAddress());
        c.setPartyLegalRep(req.partyLegalRep());
        c.setPartyContact(req.partyContact());
        c.setSummary(req.summary());
        c.setAmountInvolved(req.amountInvolved() == null ? BigDecimal.ZERO : req.amountInvolved());
        c.setFiledAt(today);
        c.setDeadlineAt(today.plusDays(90));  // 第45条：立案之日起九十日
        c.setCreatedBy(username);
        caseRepository.save(c);

        for (OfficerReq o : req.officers()) {
            jdbc.update("insert into case_officer (case_id, name, cert_no, duty) values (?,?,?,?)",
                    c.getId(), o.name(), o.certNo(), o.duty() == null ? "MEMBER" : o.duty());
        }
        if (req.clueId() != null) clueService.markFiled(req.clueId(), req.clueVerifyResult());
        return c;
    }

    // ---------- 办案人员 ----------

    @Transactional
    public void addOfficer(Long caseId, OfficerReq req) {
        requireActive(get(caseId));
        jdbc.update("insert into case_officer (case_id, name, cert_no, duty) values (?,?,?,?)",
                caseId, req.name(), req.certNo(), req.duty() == null ? "MEMBER" : req.duty());
    }

    /** 回避（第5条）：回避后在册执法人员仍不得少于两人 */
    @Transactional
    public void avoidOfficer(Long caseId, Long officerId, String reason) {
        requireActive(get(caseId));
        Integer active = jdbc.queryForObject(
                "select count(*) from case_officer where case_id = ? and avoided = false and id <> ?",
                Integer.class, caseId, officerId);
        if (active == null || active < 2)
            throw new BizException(2002, "回避后执法人员将少于两人，请先补充办案人员（第16条）");
        jdbc.update("update case_officer set avoided = true, avoid_reason = ? where id = ? and case_id = ?",
                reason, officerId, caseId);
    }

    // ---------- 证据 ----------

    public record EvidenceReq(String type, String name, String source, LocalDate obtainedAt,
                              String keeper, String note, Boolean registerHold, Boolean sealed) {}

    private static final List<String> EVIDENCE_TYPES = List.of(
            "DOCUMENT", "PHYSICAL", "AV", "EDATA", "TESTIMONY", "STATEMENT", "EXPERT", "RECORD");

    @Transactional
    public void addEvidence(Long caseId, EvidenceReq req) {
        requireActive(get(caseId));
        if (!EVIDENCE_TYPES.contains(req.type()))
            throw new BizException(2027, "证据种类须为法定八类之一（第19条）");
        LocalDate obtained = req.obtainedAt() != null ? req.obtainedAt() : LocalDate.now();
        boolean hold = Boolean.TRUE.equals(req.registerHold());
        boolean sealed = Boolean.TRUE.equals(req.sealed());
        jdbc.update("""
                insert into case_evidence (case_id, type, name, source, obtained_at, keeper, note,
                    register_hold, hold_expire_at, sealed, seal_expire_at)
                values (?,?,?,?,?,?,?,?,?,?,?)""",
                caseId, req.type(), req.name(), req.source(), obtained, req.keeper(), req.note(),
                hold, hold ? Workdays.plus(obtained, 7) : null,          // 第26条：7个工作日内作出处理决定
                sealed, sealed ? obtained.plusDays(30) : null);          // 第31条：封存不超过30日
    }

    /** 先行登记保存处理决定（第28条）：PRESERVE 保全 / SEAL 转封存 / RELEASE 解除 */
    @Transactional
    public void disposeHold(Long caseId, Long evidenceId, String disposal) {
        requireActive(get(caseId));
        if (!List.of("PRESERVE", "SEAL", "RELEASE").contains(disposal))
            throw new BizException(2028, "处理措施须为 保全/封存/解除 之一（第28条）");
        int n;
        if ("SEAL".equals(disposal)) {
            n = jdbc.update("""
                    update case_evidence set hold_disposal = ?, register_hold = false,
                        sealed = true, seal_expire_at = ? where id = ? and case_id = ? and register_hold = true""",
                    disposal, LocalDate.now().plusDays(30), evidenceId, caseId);
        } else {
            n = jdbc.update("""
                    update case_evidence set hold_disposal = ?, register_hold = false
                    where id = ? and case_id = ? and register_hold = true""", disposal, evidenceId, caseId);
        }
        if (n == 0) throw new BizException(2029, "证据不存在或不在先行登记保存状态");
    }

    /** 延长封存（第31条：可延长一次，不超过30日）或解除封存（第33条） */
    @Transactional
    public void updateSeal(Long caseId, Long evidenceId, boolean extend) {
        requireActive(get(caseId));
        if (extend) {
            int n = jdbc.update("""
                    update case_evidence set seal_extended = true, seal_expire_at = seal_expire_at + 30
                    where id = ? and case_id = ? and sealed = true and seal_extended = false""", evidenceId, caseId);
            if (n == 0) throw new BizException(2030, "封存延长仅限一次且证据须在封存中（第31条）");
        } else {
            int n = jdbc.update("update case_evidence set sealed = false where id = ? and case_id = ? and sealed = true",
                    evidenceId, caseId);
            if (n == 0) throw new BizException(2029, "证据不在封存状态");
        }
    }

    // ---------- 文书 ----------

    public record DocumentReq(String docType, String title, String content, LocalDate madeAt,
                              String maker, Boolean signed, String note) {}

    @Transactional
    public void addDocument(Long caseId, DocumentReq req) {
        CaseFile c = get(caseId);
        if ("CLOSED".equals(c.getStatus())) throw new BizException(2031, "案件已结案归档，不可新增文书");
        jdbc.update("""
                insert into case_document (case_id, doc_type, title, content, made_at, maker, signed, note)
                values (?,?,?,?,?,?,?,?)""",
                caseId, req.docType(), req.title(), req.content(),
                req.madeAt() != null ? req.madeAt() : LocalDate.now(),
                req.maker(), Boolean.TRUE.equals(req.signed()), req.note());
    }

    // ---------- 期限扣除（第45条） ----------

    public record ExclusionReq(String reason, LocalDate startAt, LocalDate endAt, String note) {}

    @Transactional
    public void addExclusion(Long caseId, ExclusionReq req) {
        requireActive(get(caseId));
        if (!List.of("TEST", "APPRAISE", "HEARING", "ANNOUNCE", "EXPERT").contains(req.reason()))
            throw new BizException(2032, "不计入期限的情形限于检测检验/鉴定/听证/公告/专家评审（第45条）");
        jdbc.update("insert into case_period_exclusion (case_id, reason, start_at, end_at, note) values (?,?,?,?,?)",
                caseId, req.reason(), req.startAt(), req.endAt(), req.note());
    }

    // ---------- 中止 / 恢复 / 终止 ----------

    @Transactional
    public CaseFile suspend(Long caseId, String reason) {
        CaseFile c = get(caseId);
        if (!"INVESTIGATING".equals(c.getStatus())) throw new BizException(2033, "仅调查中的案件可中止（第42条）");
        if (reason == null || reason.isBlank()) throw new BizException(2033, "中止须载明法定情形并经负责人批准（第42条）");
        c.setStatus("SUSPENDED");
        c.setSuspendReason(reason);
        c.setSuspendedAt(LocalDate.now());
        return caseRepository.save(c);
    }

    /** 中止原因消除后立即恢复调查；中止期间顺延办案期限 */
    @Transactional
    public CaseFile resume(Long caseId) {
        CaseFile c = get(caseId);
        if (!"SUSPENDED".equals(c.getStatus())) throw new BizException(2034, "案件不在中止状态");
        long suspendedDays = ChronoUnit.DAYS.between(c.getSuspendedAt(), LocalDate.now());
        c.setDeadlineAt(c.getDeadlineAt().plusDays(suspendedDays));
        c.setStatus("INVESTIGATING");
        c.setSuspendReason(null);
        c.setSuspendedAt(null);
        return caseRepository.save(c);
    }

    @Transactional
    public CaseFile terminate(Long caseId, String reason) {
        CaseFile c = get(caseId);
        if (List.of("DECIDED", "DELIVERED", "CLOSED", "TERMINATED").contains(c.getStatus()))
            throw new BizException(2035, "已决定/已结案的案件不可终止调查（第47条）");
        if (reason == null || reason.isBlank()) throw new BizException(2035, "终止须载明法定情形并经负责人批准（第47条）");
        // 第47条：终止调查的，已采取的强制措施应当同时解除
        jdbc.update("update case_evidence set sealed = false, register_hold = false where case_id = ?", caseId);
        c.setStatus("TERMINATED");
        c.setTerminateReason(reason);
        c.setTerminatedAt(LocalDate.now());
        return caseRepository.save(c);
    }

    // ---------- 延期（第45条：+30；集体讨论再延最长+60） ----------

    @Transactional
    public CaseFile extend(Long caseId, int days, String reason) {
        CaseFile c = get(caseId);
        requireActive(c);
        if (days <= 0) throw new BizException(2009, "延长天数须为正数");
        int already = c.getExtensionDays();
        if (already == 0) {
            if (days > 30) throw new BizException(2009, "首次延期经负责人批准最长30日（第45条）");
        } else {
            if (already + days > 90) throw new BizException(2009, "继续延期须集体讨论且累计不超过90日（30+60，第45条）");
            boolean hasMeeting = !jdbc.queryForList("select id from case_meeting where case_id = ?", caseId).isEmpty();
            if (!hasMeeting) throw new BizException(2009, "继续延期应当由负责人集体讨论决定，请先录入集体讨论记录（第45条）");
        }
        if (reason == null || reason.isBlank()) throw new BizException(2009, "延期须说明理由");
        c.setExtensionDays(already + days);
        c.setDeadlineAt(c.getDeadlineAt().plusDays(days));
        return caseRepository.save(c);
    }

    // ---------- 调查终结（第36条） ----------

    @Transactional
    public CaseFile report(Long caseId, String reportContent, String maker) {
        CaseFile c = get(caseId);
        if (!"INVESTIGATING".equals(c.getStatus())) throw new BizException(2004, "仅调查中的案件可出具调查终结报告");
        if ("SUMMARY".equals(c.getProcedureType())) throw new BizException(2004, "简易程序无需调查终结报告，可直接当场决定");
        if (reportContent == null || reportContent.isBlank())
            throw new BizException(2004, "调查终结报告须包含当事人情况/案件来源/事实证据/性质/处理意见（第36条）");
        addDocument(caseId, new DocumentReq("FINAL_REPORT", c.getCaseNo() + " 案件调查终结报告",
                reportContent, LocalDate.now(), maker, false, null));
        c.setStatus("REPORTED");
        c.setReportedAt(LocalDate.now());
        return caseRepository.save(c);
    }

    // ---------- 法制审核（第37-40条） ----------

    @Transactional
    public CaseReview submitReview(Long caseId, String requiredReason) {
        CaseFile c = get(caseId);
        if (!List.of("REPORTED", "NOTIFIED").contains(c.getStatus()))
            throw new BizException(2036, "法制审核在调查终结后、作出决定前进行（第37条）");
        CaseReview r = new CaseReview();
        r.setCaseId(caseId);
        r.setRequiredReason(requiredReason);
        r.setSubmittedAt(LocalDate.now());
        r.setDeadlineAt(Workdays.plus(LocalDate.now(), 10));  // 第40条：10个工作日
        return reviewRepository.save(r);
    }

    @Transactional
    public CaseReview doReview(Long reviewId, String reviewer, String opinionType, String opinion) {
        CaseReview r = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BizException(2037, "审核记录不存在"));
        if (r.getReviewedAt() != null) throw new BizException(2037, "该审核已办结");
        if (!List.of("AGREE", "CONTINUE", "CHANGE", "CORRECT", "OTHER").contains(opinionType))
            throw new BizException(2037, "审核意见类型须为 同意/继续调查/变更/纠正/其他（第39条）");
        // 第37条：办案人员不得作为审核人员
        List<Map<String, Object>> officers = jdbc.queryForList(
                "select name from case_officer where case_id = ?", r.getCaseId());
        if (officers.stream().anyMatch(o -> o.get("name").equals(reviewer)))
            throw new BizException(2038, "同一案件的办案人员不得作为法制审核人员（第37条）");
        r.setReviewer(reviewer);
        r.setOpinionType(opinionType);
        r.setOpinion(opinion);
        r.setReviewedAt(LocalDate.now());
        r.setPassed("AGREE".equals(opinionType));
        return reviewRepository.save(r);
    }

    // ---------- 处罚告知/陈述申辩/听证（第41条） ----------

    public record NoticeReq(String content, BigDecimal proposedFine, BigDecimal proposedRecoup) {}

    @Transactional
    public CaseNotice notify(Long caseId, NoticeReq req) {
        CaseFile c = get(caseId);
        if (!"REPORTED".equals(c.getStatus()))
            throw new BizException(2004, "调查终结后方可作出处罚告知（第36/41条）");
        CaseNotice n = new CaseNotice();
        n.setCaseId(caseId);
        n.setNotifiedAt(LocalDate.now());
        n.setContent(req.content());
        n.setProposedFine(nz(req.proposedFine()));
        n.setProposedRecoup(nz(req.proposedRecoup()));
        // 拟罚款达到听证标准：告知听证权利
        n.setHearingEntitled(nz(req.proposedFine()).compareTo(cfgDecimal("hearing_fine_threshold")) >= 0);
        noticeRepository.save(n);
        c.setStatus("NOTIFIED");
        caseRepository.save(c);
        return n;
    }

    public record StatementReq(String statement, String statementReview,
                               Boolean hearingRequested, LocalDate hearingHeldAt) {}

    @Transactional
    public CaseNotice recordStatement(Long caseId, StatementReq req) {
        CaseNotice n = noticeRepository.findTopByCaseIdOrderByIdDesc(caseId)
                .orElseThrow(() -> new BizException(2006, "尚未作出处罚告知"));
        if (req.statement() != null) n.setStatement(req.statement());
        if (req.statementReview() != null) n.setStatementReview(req.statementReview());
        if (req.hearingRequested() != null) {
            if (Boolean.TRUE.equals(req.hearingRequested()) && !Boolean.TRUE.equals(n.getHearingEntitled()))
                throw new BizException(2039, "该案未达听证标准，无听证权利告知记录");
            n.setHearingRequested(req.hearingRequested());
        }
        if (req.hearingHeldAt() != null) {
            if (!Boolean.TRUE.equals(n.getHearingRequested()))
                throw new BizException(2039, "当事人未申请听证");
            n.setHearingHeldAt(req.hearingHeldAt());
        }
        return noticeRepository.save(n);
    }

    // ---------- 集体讨论（第44条） ----------

    public record MeetingReq(LocalDate heldAt, String attendees, String record, String conclusion) {}

    @Transactional
    public void addMeeting(Long caseId, MeetingReq req) {
        get(caseId);
        jdbc.update("insert into case_meeting (case_id, held_at, attendees, record, conclusion) values (?,?,?,?,?)",
                caseId, req.heldAt() != null ? req.heldAt() : LocalDate.now(),
                req.attendees(), req.record(), req.conclusion());
    }

    // ---------- 处理决定（第43-45条） ----------

    public record DecisionReq(String decisionType, BigDecimal fineAmount, BigDecimal recoupAmount,
                              BigDecimal confiscateAmount, String otherMeasures, String content) {}

    @Transactional
    public CaseDecision decide(Long caseId, DecisionReq req) {
        CaseFile c = get(caseId);
        if (decisionRepository.findByCaseId(caseId).isPresent())
            throw new BizException(2040, "该案已作出处理决定");
        if (!List.of("PUNISH", "NO_PUNISH", "NOT_ESTABLISHED", "TRANSFER_ADMIN", "TRANSFER_JUDICIAL")
                .contains(req.decisionType()))
            throw new BizException(2040, "决定类型须为第43条规定的五种之一");

        BigDecimal fine = nz(req.fineAmount());
        BigDecimal recoup = nz(req.recoupAmount());
        boolean summary = "SUMMARY".equals(c.getProcedureType());

        if (summary) {
            if (!"INVESTIGATING".equals(c.getStatus()))
                throw new BizException(2003, "简易程序应在调查中当场作出决定（第48条）");
            // 第48条：公民≤200元，法人或其他组织≤3000元
            BigDecimal limit = "INDIVIDUAL".equals(c.getPartyType())
                    ? new BigDecimal("200") : new BigDecimal("3000");
            if ("PUNISH".equals(req.decisionType()) && fine.compareTo(limit) > 0)
                throw new BizException(2003, "简易程序罚款限额：公民200元、法人或其他组织3000元，超限请转普通程序（第48条）");
        } else {
            if (!"NOTIFIED".equals(c.getStatus()))
                throw new BizException(2006, "作出处罚决定前应当书面告知当事人并听取陈述申辩（第41条）");
            CaseNotice notice = noticeRepository.findTopByCaseIdOrderByIdDesc(caseId)
                    .orElseThrow(() -> new BizException(2006, "缺少处罚告知记录（第41条）"));
            // 第41条：不得因陈述、申辩或申请听证而加重处罚
            if (fine.compareTo(notice.getProposedFine()) > 0 || recoup.compareTo(notice.getProposedRecoup()) > 0)
                throw new BizException(2007, "决定金额不得高于告知金额——不得因陈述申辩而加重处罚（第41条）");
            // 第37条：罚款数额较大或经过听证的案件，未经法制审核或审核未通过，不得作出决定
            boolean needReview = fine.compareTo(cfgDecimal("legal_review_fine_threshold")) >= 0
                    || notice.getHearingHeldAt() != null;
            if (needReview) {
                CaseReview review = reviewRepository.findTopByCaseIdOrderByIdDesc(caseId).orElse(null);
                if (review == null || !Boolean.TRUE.equals(review.getPassed()))
                    throw new BizException(2005, "罚款数额较大或经听证的案件，未经法制审核通过不得作出决定（第37条）");
            }
            // 第45条：办案期限（含批准延长），扣除期间不计入
            LocalDate effectiveDeadline = c.getDeadlineAt().plusDays(totalExclusionDays(caseId));
            if (LocalDate.now().isAfter(effectiveDeadline))
                throw new BizException(2008, "已超办案期限（" + effectiveDeadline + "），请先办理延期审批（第45条）");
        }

        CaseDecision d = new CaseDecision();
        d.setCaseId(caseId);
        d.setDecisionType(req.decisionType());
        if ("PUNISH".equals(req.decisionType())) {
            String prefix = cfg("decision_no_prefix", "医保罚") + "〔" + LocalDate.now().getYear() + "〕";
            d.setDecisionNo(prefix + (decisionRepository.countByDecisionNoStartingWith(prefix) + 1) + "号");
        }
        d.setFineAmount(fine);
        d.setRecoupAmount(recoup);
        d.setConfiscateAmount(nz(req.confiscateAmount()));
        d.setOtherMeasures(req.otherMeasures());
        d.setContent(req.content());
        d.setDecidedAt(LocalDate.now());
        decisionRepository.save(d);

        c.setStatus("DECIDED");
        c.setDecidedAt(LocalDate.now());
        c.setName(c.getName().replace("涉嫌", ""));  // 苏医保督〔2024〕1号：决定后案件名称去"涉嫌"
        caseRepository.save(c);
        return d;
    }

    // ---------- 送达（第59条） ----------

    public record DeliveryReq(String method, LocalDate deliveredAt, String receiver, String note) {}

    @Transactional
    public CaseFile deliver(Long caseId, DeliveryReq req) {
        CaseFile c = get(caseId);
        if (!"DECIDED".equals(c.getStatus())) throw new BizException(2041, "仅已决定的案件可登记送达（第59条）");
        if (!List.of("DIRECT", "MAIL", "LEFT", "ELECTRONIC", "ANNOUNCE").contains(req.method()))
            throw new BizException(2041, "送达方式须为 直接/邮寄/留置/电子/公告 之一");
        jdbc.update("insert into case_delivery (case_id, method, delivered_at, receiver, note) values (?,?,?,?,?)",
                caseId, req.method(), req.deliveredAt() != null ? req.deliveredAt() : LocalDate.now(),
                req.receiver(), req.note());
        c.setStatus("DELIVERED");
        c.setDeliveredAt(req.deliveredAt() != null ? req.deliveredAt() : LocalDate.now());
        return caseRepository.save(c);
    }

    // ---------- 执行（第52-55条） ----------

    public record ExecutionReq(String kind, BigDecimal amount, LocalDate paidAt, String method, String note) {}

    @Transactional
    public void addExecution(Long caseId, ExecutionReq req) {
        CaseFile c = get(caseId);
        if (!List.of("DECIDED", "DELIVERED").contains(c.getStatus()))
            throw new BizException(2042, "仅已决定/已送达的案件可登记执行");
        CaseDecision d = decisionRepository.findByCaseId(caseId)
                .orElseThrow(() -> new BizException(2042, "案件无处理决定"));
        if (!List.of("FINE", "RECOUP", "CONFISCATE", "LATE_FEE").contains(req.kind()))
            throw new BizException(2042, "执行类型须为 罚款/退回基金/没收违法所得/加处罚款");
        BigDecimal amount = nz(req.amount());
        if (amount.signum() <= 0) throw new BizException(2042, "执行金额须为正数");
        // 第52条：当场收缴限一百元以下（或不当场收缴事后难以执行——系统按金额硬校验）
        if ("ONSITE".equals(req.method()) && amount.compareTo(new BigDecimal("100")) > 0)
            throw new BizException(2010, "当场收缴罚款限一百元以下（第52条）");
        // 第55条：加处罚款不得超出罚款数额
        if ("LATE_FEE".equals(req.kind())) {
            BigDecimal paidLateFee = sumExecution(caseId, "LATE_FEE");
            if (paidLateFee.add(amount).compareTo(d.getFineAmount()) > 0)
                throw new BizException(2012, "加处罚款累计不得超出罚款数额（第55条）");
        }
        jdbc.update("insert into case_execution (case_id, kind, amount, paid_at, method, note) values (?,?,?,?,?,?)",
                caseId, req.kind(), amount, req.paidAt() != null ? req.paidAt() : LocalDate.now(),
                req.method(), req.note());
    }

    /** 加处罚款测算（第55条：每日按罚款数额3%加处，不超出罚款数额；缴款期限=送达后15日） */
    public Map<String, Object> lateFeeQuote(Long caseId) {
        CaseFile c = get(caseId);
        CaseDecision d = decisionRepository.findByCaseId(caseId)
                .orElseThrow(() -> new BizException(2042, "案件无处理决定"));
        if (c.getDeliveredAt() == null) throw new BizException(2042, "决定书尚未送达");
        LocalDate payDeadline = c.getDeliveredAt().plusDays(15);
        long overdueDays = Math.max(0, ChronoUnit.DAYS.between(payDeadline, LocalDate.now()));
        BigDecimal accrued = d.getFineAmount().multiply(new BigDecimal("0.03"))
                .multiply(BigDecimal.valueOf(overdueDays));
        BigDecimal capped = accrued.min(d.getFineAmount());
        return Map.of("payDeadline", payDeadline, "overdueDays", overdueDays,
                "accrued", accrued, "capped", capped, "fineAmount", d.getFineAmount());
    }

    /** 暂缓/分期缴纳批准（第54条） */
    @Transactional
    public CaseFile approveDefer(Long caseId) {
        CaseFile c = get(caseId);
        if (!List.of("DECIDED", "DELIVERED").contains(c.getStatus()))
            throw new BizException(2042, "仅已决定/已送达的案件可批准暂缓分期");
        c.setDeferApproved(true);
        return caseRepository.save(c);
    }

    /** 申请法院强制执行（第55条） */
    @Transactional
    public CaseFile applyCourtEnforce(Long caseId) {
        CaseFile c = get(caseId);
        if (!"DELIVERED".equals(c.getStatus())) throw new BizException(2042, "决定书送达且当事人逾期不履行方可申请强制执行");
        c.setCourtEnforceApplied(true);
        return caseRepository.save(c);
    }

    // ---------- 结案（第56-57条） ----------

    @Transactional
    public CaseFile close(Long caseId, String closeReport, String maker) {
        CaseFile c = get(caseId);
        CaseDecision d = decisionRepository.findByCaseId(caseId)
                .orElseThrow(() -> new BizException(2011, "无处理决定，不能结案"));
        if (!List.of("DECIDED", "DELIVERED").contains(c.getStatus()))
            throw new BizException(2011, "案件状态不满足结案条件");

        String closeReason;
        if ("PUNISH".equals(d.getDecisionType())) {
            if (!"DELIVERED".equals(c.getStatus()))
                throw new BizException(2011, "处罚决定书未送达，不能结案（第59条）");
            if (Boolean.TRUE.equals(c.getCourtEnforceApplied())) {
                closeReason = "COURT";     // 第56条(二)：申请法院强制执行且受理
            } else if (fullyExecuted(caseId, d)) {
                closeReason = "EXECUTED";  // 第56条(一)：执行完毕
            } else {
                throw new BizException(2011, "罚没款项/退回基金未执行完毕，且未申请法院强制执行，不能结案（第56条）");
            }
        } else {
            closeReason = "NO_NEED";       // 第56条(三)：不予处罚等无须执行
        }

        if (closeReport == null || closeReport.isBlank())
            throw new BizException(2011, "须填写行政处罚结案报告并经负责人批准（第56条）");
        addDocument(caseId, new DocumentReq("CLOSE_REPORT", c.getCaseNo() + " 结案报告",
                closeReport, LocalDate.now(), maker, false, null));

        c.setStatus("CLOSED");
        c.setClosedAt(LocalDate.now());
        c.setCloseReason(closeReason);
        c.setArchiveNo(c.getCaseNo() + "卷");  // 第57条：一案一卷
        return caseRepository.save(c);
    }

    // ---------- 查询 ----------

    public CaseFile get(Long id) {
        return caseRepository.findById(id).orElseThrow(() -> new BizException(2043, "案件不存在"));
    }

    public Map<String, Object> detail(Long id) {
        CaseFile c = get(id);
        CaseCause cause = causeRepository.findById(c.getCauseId()).orElse(null);
        return Map.ofEntries(
                Map.entry("caseFile", c),
                Map.entry("cause", cause == null ? Map.of() : cause),
                Map.entry("officers", jdbc.queryForList("select * from case_officer where case_id = ? order by id", id)),
                Map.entry("evidences", jdbc.queryForList("select * from case_evidence where case_id = ? order by id", id)),
                Map.entry("documents", jdbc.queryForList(
                        "select id, doc_type, title, made_at, maker, signed, note from case_document where case_id = ? order by id", id)),
                Map.entry("exclusions", jdbc.queryForList("select * from case_period_exclusion where case_id = ? order by id", id)),
                Map.entry("reviews", reviewRepository.findByCaseIdOrderByIdDesc(id)),
                Map.entry("notices", noticeRepository.findByCaseIdOrderByIdDesc(id)),
                Map.entry("meetings", jdbc.queryForList("select * from case_meeting where case_id = ? order by id", id)),
                Map.entry("decision", decisionRepository.findByCaseId(id).map(Object.class::cast).orElse(Map.of())),
                Map.entry("deliveries", jdbc.queryForList("select * from case_delivery where case_id = ? order by id", id)),
                Map.entry("executions", jdbc.queryForList("select * from case_execution where case_id = ? order by id", id)),
                Map.entry("effectiveDeadline", c.getDeadlineAt().plusDays(totalExclusionDays(id))));
    }

    public Map<String, Object> documentDetail(Long caseId, Long docId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from case_document where id = ? and case_id = ?", docId, caseId);
        if (rows.isEmpty()) throw new BizException(2045, "文书不存在");
        return rows.get(0);
    }

    // ---------- 私有 ----------

    private void requireActive(CaseFile c) {
        if (!"INVESTIGATING".equals(c.getStatus()) && !"REPORTED".equals(c.getStatus())
                && !"NOTIFIED".equals(c.getStatus()))
            throw new BizException(2044, "案件当前状态（" + c.getStatus() + "）不允许该操作");
    }

    private long totalExclusionDays(Long caseId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select start_at, end_at from case_period_exclusion where case_id = ? and end_at is not null", caseId);
        return rows.stream().mapToLong(r -> ChronoUnit.DAYS.between(
                ((java.sql.Date) r.get("start_at")).toLocalDate(),
                ((java.sql.Date) r.get("end_at")).toLocalDate())).sum();
    }

    private boolean fullyExecuted(Long caseId, CaseDecision d) {
        return sumExecution(caseId, "FINE").compareTo(d.getFineAmount()) >= 0
                && sumExecution(caseId, "RECOUP").compareTo(d.getRecoupAmount()) >= 0
                && sumExecution(caseId, "CONFISCATE").compareTo(d.getConfiscateAmount()) >= 0;
    }

    private BigDecimal sumExecution(Long caseId, String kind) {
        BigDecimal v = jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from case_execution where case_id = ? and kind = ?",
                BigDecimal.class, caseId, kind);
        return v == null ? BigDecimal.ZERO : v;
    }

    private String cfg(String key, String def) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select cfg_value from sys_config where cfg_key = ?", key);
        return rows.isEmpty() ? def : (String) rows.get(0).get("cfg_value");
    }

    private BigDecimal cfgDecimal(String key) {
        return new BigDecimal(cfg(key, "100000"));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
