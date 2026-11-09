package cn.hip.server.web;

import cn.hip.outpatient.service.EmrOfdPreviewService;
import cn.hip.outpatient.service.EmrOfdDocumentService;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.NoSuchElementException;
import java.util.Map;

/** Unsigned OFD preview/retention routes with explicit JSON errors, including OFD-only Accept. */
@RestController
@RequestMapping("/api/emr/versions")
@RequiredArgsConstructor
public class EmrOfdPreviewController {
    private final EmrOfdPreviewService previews;
    private final EmrOfdDocumentService documents;
    private final GlobalExceptionHandler errors;

    // Apply entry roles only to the download; error handlers must not be authorized again.
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','QUALITY')")
    @GetMapping("/INP/{emrId}/versions/{versionNo}/ofd-preview")
    public ResponseEntity<byte[]> preview(@PathVariable Long emrId, @PathVariable Integer versionNo) {
        byte[] document = previews.preview(emrId, versionNo);
        String filename = "emr-INP-" + emrId + "-v" + versionNo + "-preview.ofd";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/ofd"))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(document);
    }

    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','QUALITY')")
    @PostMapping("/INP/{emrId}/versions/{versionNo}/ofd-documents")
    public ResponseEntity<R<Map<String, Object>>> create(@PathVariable Long emrId, @PathVariable Integer versionNo) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .body(R.ok(documents.create(emrId, versionNo)));
    }

    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','QUALITY')")
    @GetMapping("/INP/{emrId}/versions/{versionNo}/ofd-documents")
    public ResponseEntity<R<EmrOfdDocumentService.DocumentPage>> list(@PathVariable Long emrId,
                                                                     @PathVariable Integer versionNo,
                                                                     @RequestParam(required = false) String before) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .body(R.ok(documents.list(emrId, versionNo, before)));
    }

    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','QUALITY')")
    @GetMapping("/INP/{emrId}/versions/{versionNo}/ofd-documents/{documentId}/content")
    public ResponseEntity<byte[]> content(@PathVariable Long emrId, @PathVariable Integer versionNo,
                                          @PathVariable String documentId) {
        byte[] document = documents.content(emrId, versionNo, documentId);
        String filename = "emr-INP-" + emrId + "-v" + versionNo + "-" + documentId + ".ofd";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/ofd"))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString()).body(document);
    }

    @ExceptionHandler(HipBizException.class)
    public ResponseEntity<R<Void>> business(HipBizException error) {
        return json(HttpStatus.OK, errors.handleBiz(error));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<R<Void>> duplicate(DuplicateKeyException error) {
        return json(HttpStatus.OK, errors.handleDuplicate(error));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<R<Void>> integrity(DataIntegrityViolationException error) {
        return json(HttpStatus.OK, errors.handleIntegrity(error));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MethodArgumentNotValidException.class,
            IllegalArgumentException.class, NumberFormatException.class,
            java.time.format.DateTimeParseException.class})
    public ResponseEntity<R<Void>> badRequest(Exception error) {
        return json(HttpStatus.OK, errors.handleBadRequest(error));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<R<Void>> notFound(NoSuchElementException error) {
        return json(HttpStatus.OK, errors.handleNotFound(error));
    }

    // Preserve the original SecurityConfig JSON entry point / access-denied handlers.
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    public void security(RuntimeException error) {
        errors.rethrowSecurity(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> unexpected(Exception error) {
        // The delegate's @ResponseStatus is not applied on a plain Java call.
        return json(HttpStatus.INTERNAL_SERVER_ERROR, errors.handleOther(error));
    }

    private ResponseEntity<R<Void>> json(HttpStatus status, R<Void> body) {
        // Concrete Content-Type keeps an OFD-only Accept from re-negotiating this error.
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(body);
    }
}
