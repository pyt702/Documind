package com.accenture.intern.docmind.controller;

import com.accenture.intern.docmind.dto.session.CreateSessionRequest;
import com.accenture.intern.docmind.dto.session.RenameSessionRequest;
import com.accenture.intern.docmind.dto.session.SessionResponse;
import com.accenture.intern.docmind.dto.session.MessageResponse;
import com.accenture.intern.docmind.dto.session.PaginatedMessageResponse;
import com.accenture.intern.docmind.dto.session.SuggestedQuestionsResponse;
import com.accenture.intern.docmind.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;

import java.security.Principal;
import java.util.List;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Handles session management endpoints.
 * Implementation to be completed by the assigned developer.
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    // POST /api/sessions
    @PostMapping
    public ResponseEntity<SessionResponse> createSession(
            @RequestBody CreateSessionRequest request,
            Principal principal
    ){
        SessionResponse response=sessionService.createSession(principal.getName(),request);
        return ResponseEntity.ok(response);
    }

    // GET /api/sessions
    @GetMapping
    public ResponseEntity<List<SessionResponse>> getAllSessions(
            Principal principal
    ) {
        List<SessionResponse> sessions=sessionService.getAllSessions(principal.getName());
        return ResponseEntity.ok(sessions);
    }
   

    // DELETE /api/sessions/{id}
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSession(
            @PathVariable Long id,
            Principal principal
    ) {
        sessionService.deleteSession(principal.getName(),id);
        return ResponseEntity.noContent().build();
    }

    // PUT /api/sessions/{id}/rename
    @PutMapping("/{id}/rename")
    public ResponseEntity<SessionResponse> renameSession(
            @PathVariable Long id,
            @RequestBody RenameSessionRequest request,
            Principal principal
    ) {
        SessionResponse response = sessionService.renameSession(principal.getName(), id, request.getTitle());
        return ResponseEntity.ok(response);
    }

    // GET /api/sessions/{id}/messages
    @GetMapping("/{id}/messages")
    public ResponseEntity<PaginatedMessageResponse> getSessionMessages(
            @PathVariable Long id,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int size,
            Principal principal
    ) {
        PaginatedMessageResponse response = sessionService.getSessionMessages(principal.getName(), id, cursor, size);
        return ResponseEntity.ok(response);
    }

    // GET /api/sessions/{id}/suggested-questions
    // Polled by the frontend after a document/Wikipedia upload. Returns
    // status: NOT_STARTED | GENERATING | READY | FAILED, plus the 3 questions
    // once status is READY.
    @GetMapping("/{id}/suggested-questions")
    public ResponseEntity<SuggestedQuestionsResponse> getSuggestedQuestions(
            @PathVariable Long id,
            Principal principal
    ) {
        SuggestedQuestionsResponse response = sessionService.getSuggestedQuestions(principal.getName(), id);
        return ResponseEntity.ok(response);
    }

    // GET /api/sessions/{id}/export
    @GetMapping("/{id}/export")
    public Mono<ResponseEntity<byte[]>> exportSession(
            @PathVariable Long id,
            Principal principal
    ) {
        // Markdown export builds the full session and calls the LLM summary service,
        // which is blocking because Spring AI's ChatClient.call() is synchronous.
        // In WebFlux, never run that work on the request/event-loop thread.
        return Mono.fromCallable(() -> {
                    byte[] markdown = sessionService.exportSessionToMarkdown(principal.getName(), id);
                    org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                    headers.add("Content-Disposition", "attachment; filename=\"session-" + id + ".md\"");
                    headers.add("Content-Type", "text/markdown; charset=UTF-8");
                    return ResponseEntity.ok()
                            .headers(headers)
                            .body(markdown);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // POST /api/sessions/{id}/share/email
    @PostMapping("/{id}/share/email")
    public Mono<ResponseEntity<?>> shareSessionViaEmail(
            @PathVariable Long id,
            @RequestBody java.util.Map<String, String> payload,
            Principal principal
    ) {
        String targetEmail = payload.get("email");
        if (targetEmail == null || targetEmail.isBlank()) {
            return Mono.<ResponseEntity<?>>just(ResponseEntity.badRequest().body("Target email is required"));
        }

        // Sharing reuses markdown export and sends email, both blocking operations.
        // Offload them so WebFlux does not throw IllegalStateException for block().
        return Mono.<ResponseEntity<?>>fromCallable(() -> {
                    // This will throw if the session doesn't belong to the user
                    sessionService.shareSessionViaEmail(principal.getName(), id, targetEmail);
                    return ResponseEntity.ok(java.util.Map.of(
                            "success", true,
                            "message", "Email is being sent in the background."
                    ));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // POST /api/sessions/{id}/export-pdf
    // Queues a PDF export job onto a Redis stream and returns immediately with
    // a jobId — PDF generation (LLM summary + rendering) is too heavy to run
    // inline on the request thread. See PdfExportWorkerService.
    @PostMapping("/{id}/export-pdf")
    public ResponseEntity<com.accenture.intern.docmind.dto.session.PdfExportJobResponse> requestPdfExport(
            @PathVariable Long id,
            Principal principal
    ) {
        return ResponseEntity.ok(sessionService.requestPdfExport(principal.getName(), id));
    }

    // GET /api/sessions/{id}/export-pdf/{jobId}
    // Polled by the frontend until status is READY (with a downloadPath) or FAILED.
    @GetMapping("/{id}/export-pdf/{jobId}")
    public ResponseEntity<com.accenture.intern.docmind.dto.session.PdfExportStatusResponse> getPdfExportStatus(
            @PathVariable Long id,
            @PathVariable String jobId,
            Principal principal
    ) {
        return ResponseEntity.ok(sessionService.getPdfExportStatus(principal.getName(), id, jobId));
    }

    // GET /api/sessions/{id}/export-pdf/{jobId}/download
    // Streams the rendered PDF bytes straight from this backend (no cloud
    // storage involved) once the frontend has seen status == READY. Returns
    // 404 if the job result has expired or never completed.
    @GetMapping("/{id}/export-pdf/{jobId}/download")
    public ResponseEntity<byte[]> downloadPdfExport(
            @PathVariable Long id,
            @PathVariable String jobId,
            Principal principal
    ) {
        byte[] pdfBytes = sessionService.downloadPdfExport(principal.getName(), id, jobId);
        if (pdfBytes == null) {
            return ResponseEntity.notFound().build();
        }
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("Content-Disposition", "attachment; filename=\"session-" + id + ".pdf\"");
        headers.add("Content-Type", "application/pdf");
        return ResponseEntity.ok()
                .headers(headers)
                .body(pdfBytes);
    }
}
