package cn.hip.cdr.web;

import cn.hip.cdr.repository.CdrDocumentRepository;
import cn.hip.cdr.service.CdrSyncService;
import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/cdr")
@RequiredArgsConstructor
public class CdrController {

    private final CdrSyncService syncService;
    private final CdrDocumentRepository docRepository;

    @PostMapping("/sync")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Map<String, Integer>> sync() {
        return R.ok(syncService.syncAll());
    }

    @GetMapping("/patients/{patientId}/documents")
    public R<Object> documents(@PathVariable Long patientId,
                               @RequestParam(required = false) String docType) {
        return R.ok(syncService.patientDocuments(patientId, docType));
    }

    @GetMapping("/stats")
    public R<Map<String, Long>> stats() {
        return R.ok(Map.of(
                "OUTP_ENCOUNTER", docRepository.countByDocType("OUTP_ENCOUNTER"),
                "LAB_REPORT", docRepository.countByDocType("LAB_REPORT"),
                "INP_SUMMARY", docRepository.countByDocType("INP_SUMMARY")));
    }
}
