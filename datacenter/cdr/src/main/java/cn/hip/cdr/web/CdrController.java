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
    private final cn.hip.cdr.service.CdrLegacyService legacyService;

    @PostMapping("/sync")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Map<String, Integer>> sync() {
        return R.ok(syncService.syncAll());
    }

    @GetMapping("/patients/{patientId}/documents")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','QUALITY')")   // 1.0.9(A-6)：与 /search 同口径，内容含病历原文
    public R<Object> documents(@PathVariable Long patientId,
                               @RequestParam(required = false) String docType) {
        return R.ok(syncService.patientDocuments(patientId, docType));
    }

    /** 单文档明细（含 content 全文）：列表已投影化，点开才取全文（1.1.7） */
    @GetMapping("/documents/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','QUALITY')")
    public R<Object> document(@PathVariable Long id) {
        var doc = syncService.document(id);
        return doc == null ? R.fail(4040, "文档不存在") : R.ok(doc);
    }

    /** 病历全文检索（ILIKE 起步版）；1.0.6：内容含主诉/现病史/诊断，限临床与质控角色 */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','QUALITY')")
    public R<Object> search(@RequestParam String keyword) {
        return R.ok(docRepository.search(keyword, org.springframework.data.domain.PageRequest.of(0, 50)));
    }

    /** 增量同步（updated_at 水位） */
    @PostMapping("/sync-incremental")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Map<String, Integer>> syncIncremental() {
        return R.ok(syncService.syncIncremental());
    }

    /** CDA 样式 XML 输出（WS/T 500 结构骨架，字段映射简化版）；证件号仅 ADMIN 明文（EMPI 同口径） */
    @GetMapping(value = "/documents/{id}/cda", produces = "application/xml;charset=UTF-8")
    @PreAuthorize("hasAnyRole('ADMIN','QUALITY')")
    public String cda(@PathVariable Long id, org.springframework.security.core.Authentication auth) {
        boolean admin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return syncService.toCda(id, admin);
    }

    @GetMapping("/stats")
    public R<Map<String, Long>> stats() {
        return R.ok(Map.of(
                "OUTP_ENCOUNTER", docRepository.countByDocType("OUTP_ENCOUNTER"),
                "LAB_REPORT", docRepository.countByDocType("LAB_REPORT"),
                "INP_SUMMARY", docRepository.countByDocType("INP_SUMMARY"),
                "LEGACY", docRepository.countByDocTypeStartingWith("LEGACY_")));
    }

    /** 存量切换：老 HIS 历史文书导入（幂等，键=类别+老单号；tools/import-documents.py 调用，1.1.5） */
    @PostMapping("/legacy-documents")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Map<String, Object>> importLegacy(@RequestBody cn.hip.cdr.service.CdrLegacyService.LegacyDoc req) {
        try {
            return R.ok(legacyService.importDocument(req));
        } catch (cn.hip.cdr.service.CdrLegacyService.LegacyException e) {
            return R.fail(e.code, e.getMessage());
        }
    }
}
