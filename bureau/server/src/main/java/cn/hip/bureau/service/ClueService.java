package cn.hip.bureau.service;

import cn.hip.bureau.common.BizException;
import cn.hip.bureau.common.Workdays;
import cn.hip.bureau.entity.CaseClue;
import cn.hip.bureau.repository.CaseClueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/** 线索管理（第14条：15个工作日内核查并决定是否立案，特殊情况经主要负责人批准延长15个工作日） */
@Service
@RequiredArgsConstructor
public class ClueService {

    private final CaseClueRepository clueRepository;

    public record ClueCreateReq(String source, String content, String suspectName,
                                String suspectType, LocalDate receivedAt, String handler) {}

    @Transactional
    public CaseClue create(ClueCreateReq req, String username) {
        if (req.content() == null || req.content().isBlank()) throw new BizException(2020, "线索内容不能为空");
        LocalDate received = req.receivedAt() != null ? req.receivedAt() : LocalDate.now();
        CaseClue clue = new CaseClue();
        String prefix = "XS" + received.getYear();
        clue.setClueNo(prefix + String.format("%04d", clueRepository.countByClueNoStartingWith(prefix) + 1));
        clue.setSource(req.source());
        clue.setContent(req.content());
        clue.setSuspectName(req.suspectName());
        clue.setSuspectType(req.suspectType());
        clue.setReceivedAt(received);
        clue.setDeadlineAt(Workdays.plus(received, 15));
        clue.setHandler(req.handler());
        clue.setCreatedBy(username);
        return clueRepository.save(clue);
    }

    /** 延长核查期限：仅限一次，再延 15 个工作日（第14条） */
    @Transactional
    public CaseClue extend(Long id, String reason) {
        CaseClue clue = get(id);
        if (!"PENDING".equals(clue.getStatus())) throw new BizException(2021, "线索已办结，不可延期");
        if (Boolean.TRUE.equals(clue.getExtended())) throw new BizException(2022, "核查期限只能延长一次（第14条）");
        if (reason == null || reason.isBlank()) throw new BizException(2023, "延期须说明特殊情况并经主要负责人批准");
        clue.setExtended(true);
        clue.setExtendReason(reason);
        clue.setDeadlineAt(Workdays.plus(clue.getDeadlineAt(), 15));
        return clueRepository.save(clue);
    }

    /** 不予立案 */
    @Transactional
    public CaseClue reject(Long id, String verifyResult) {
        CaseClue clue = get(id);
        if (!"PENDING".equals(clue.getStatus())) throw new BizException(2021, "线索已办结");
        if (verifyResult == null || verifyResult.isBlank()) throw new BizException(2024, "不予立案须记录核查结果");
        clue.setStatus("REJECTED");
        clue.setVerifyResult(verifyResult);
        return clueRepository.save(clue);
    }

    /** 立案时由 CaseService 回调标记 */
    @Transactional
    public void markFiled(Long id, String verifyResult) {
        CaseClue clue = get(id);
        if (!"PENDING".equals(clue.getStatus())) throw new BizException(2021, "线索已办结，不可重复立案");
        clue.setStatus("FILED");
        if (verifyResult != null && !verifyResult.isBlank()) clue.setVerifyResult(verifyResult);
        clueRepository.save(clue);
    }

    public CaseClue get(Long id) {
        return clueRepository.findById(id).orElseThrow(() -> new BizException(2025, "线索不存在"));
    }
}
