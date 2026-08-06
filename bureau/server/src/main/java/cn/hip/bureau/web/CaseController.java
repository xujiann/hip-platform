package cn.hip.bureau.web;

import cn.hip.bureau.entity.*;
import cn.hip.bureau.repository.CaseFileRepository;
import cn.hip.bureau.service.CaseService;
import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bureau/cases")
@RequiredArgsConstructor
public class CaseController {

    private final CaseService caseService;
    private final CaseFileRepository caseRepository;

    @GetMapping
    public R<List<CaseFile>> list(@RequestParam(required = false) String status) {
        return R.ok(status == null ? caseRepository.findTop200ByOrderByIdDesc()
                : caseRepository.findByStatusOrderByIdDesc(status));
    }

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(caseService.detail(id));
    }

    @PostMapping
    public R<CaseFile> create(@RequestBody CaseService.CaseCreateReq req, Authentication auth) {
        return R.ok(caseService.create(req, auth.getName()));
    }

    @PostMapping("/{id}/officers")
    public R<Void> addOfficer(@PathVariable Long id, @RequestBody CaseService.OfficerReq req) {
        caseService.addOfficer(id, req);
        return R.ok();
    }

    @PostMapping("/{id}/officers/{officerId}/avoid")
    public R<Void> avoidOfficer(@PathVariable Long id, @PathVariable Long officerId,
                                @RequestBody Map<String, String> body) {
        caseService.avoidOfficer(id, officerId, body.get("reason"));
        return R.ok();
    }

    @PostMapping("/{id}/evidences")
    public R<Void> addEvidence(@PathVariable Long id, @RequestBody CaseService.EvidenceReq req) {
        caseService.addEvidence(id, req);
        return R.ok();
    }

    @PostMapping("/{id}/evidences/{evidenceId}/hold-disposal")
    public R<Void> disposeHold(@PathVariable Long id, @PathVariable Long evidenceId,
                               @RequestBody Map<String, String> body) {
        caseService.disposeHold(id, evidenceId, body.get("disposal"));
        return R.ok();
    }

    @PostMapping("/{id}/evidences/{evidenceId}/seal")
    public R<Void> updateSeal(@PathVariable Long id, @PathVariable Long evidenceId,
                              @RequestParam boolean extend) {
        caseService.updateSeal(id, evidenceId, extend);
        return R.ok();
    }

    @PostMapping("/{id}/documents")
    public R<Void> addDocument(@PathVariable Long id, @RequestBody CaseService.DocumentReq req) {
        caseService.addDocument(id, req);
        return R.ok();
    }

    @GetMapping("/{id}/documents/{docId}")
    public R<Map<String, Object>> document(@PathVariable Long id, @PathVariable Long docId) {
        return R.ok(caseService.documentDetail(id, docId));
    }

    @PostMapping("/{id}/exclusions")
    public R<Void> addExclusion(@PathVariable Long id, @RequestBody CaseService.ExclusionReq req) {
        caseService.addExclusion(id, req);
        return R.ok();
    }

    @PostMapping("/{id}/suspend")
    public R<CaseFile> suspend(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return R.ok(caseService.suspend(id, body.get("reason")));
    }

    @PostMapping("/{id}/resume")
    public R<CaseFile> resume(@PathVariable Long id) {
        return R.ok(caseService.resume(id));
    }

    @PostMapping("/{id}/terminate")
    public R<CaseFile> terminate(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return R.ok(caseService.terminate(id, body.get("reason")));
    }

    @PostMapping("/{id}/extend")
    public R<CaseFile> extend(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return R.ok(caseService.extend(id, ((Number) body.get("days")).intValue(),
                (String) body.get("reason")));
    }

    @PostMapping("/{id}/report")
    public R<CaseFile> report(@PathVariable Long id, @RequestBody Map<String, String> body,
                              Authentication auth) {
        return R.ok(caseService.report(id, body.get("content"), auth.getName()));
    }

    @PostMapping("/{id}/reviews")
    public R<CaseReview> submitReview(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return R.ok(caseService.submitReview(id, body.get("requiredReason")));
    }

    @PostMapping("/reviews/{reviewId}")
    public R<CaseReview> doReview(@PathVariable Long reviewId, @RequestBody Map<String, String> body) {
        return R.ok(caseService.doReview(reviewId, body.get("reviewer"),
                body.get("opinionType"), body.get("opinion")));
    }

    @PostMapping("/{id}/notice")
    public R<CaseNotice> notify(@PathVariable Long id, @RequestBody CaseService.NoticeReq req) {
        return R.ok(caseService.notify(id, req));
    }

    @PostMapping("/{id}/statement")
    public R<CaseNotice> recordStatement(@PathVariable Long id, @RequestBody CaseService.StatementReq req) {
        return R.ok(caseService.recordStatement(id, req));
    }

    @PostMapping("/{id}/meetings")
    public R<Void> addMeeting(@PathVariable Long id, @RequestBody CaseService.MeetingReq req) {
        caseService.addMeeting(id, req);
        return R.ok();
    }

    @PostMapping("/{id}/decide")
    public R<CaseDecision> decide(@PathVariable Long id, @RequestBody CaseService.DecisionReq req) {
        return R.ok(caseService.decide(id, req));
    }

    @PostMapping("/{id}/deliver")
    public R<CaseFile> deliver(@PathVariable Long id, @RequestBody CaseService.DeliveryReq req) {
        return R.ok(caseService.deliver(id, req));
    }

    @PostMapping("/{id}/executions")
    public R<Void> addExecution(@PathVariable Long id, @RequestBody CaseService.ExecutionReq req) {
        caseService.addExecution(id, req);
        return R.ok();
    }

    @GetMapping("/{id}/late-fee-quote")
    public R<Map<String, Object>> lateFeeQuote(@PathVariable Long id) {
        return R.ok(caseService.lateFeeQuote(id));
    }

    @PostMapping("/{id}/approve-defer")
    public R<CaseFile> approveDefer(@PathVariable Long id) {
        return R.ok(caseService.approveDefer(id));
    }

    @PostMapping("/{id}/court-enforce")
    public R<CaseFile> applyCourtEnforce(@PathVariable Long id) {
        return R.ok(caseService.applyCourtEnforce(id));
    }

    @PostMapping("/{id}/close")
    public R<CaseFile> close(@PathVariable Long id, @RequestBody Map<String, String> body,
                             Authentication auth) {
        return R.ok(caseService.close(id, body.get("closeReport"), auth.getName()));
    }
}
