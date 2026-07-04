package com.accenture.intern.docmind.service;

import com.accenture.intern.docmind.aiservices.context.SessionSummaryService;
import com.accenture.intern.docmind.dto.chat.SessionUploadState;
import com.accenture.intern.docmind.dto.session.CreateSessionRequest;
import com.accenture.intern.docmind.dto.session.PdfExportJobResponse;
import com.accenture.intern.docmind.dto.session.PdfExportStatusResponse;
import com.accenture.intern.docmind.dto.session.SessionResponse;
import com.accenture.intern.docmind.dto.session.SuggestedQuestionsResponse;
import com.accenture.intern.docmind.dto.session.PaginatedMessageResponse;
import com.accenture.intern.docmind.entity.Session;
import com.accenture.intern.docmind.entity.User;
import com.accenture.intern.docmind.entity.Message;
import com.accenture.intern.docmind.dto.session.MessageResponse;
import com.accenture.intern.docmind.repository.AttachmentRepository;
import com.accenture.intern.docmind.repository.SessionRepository;
import com.accenture.intern.docmind.repository.UserRepository;
import com.accenture.intern.docmind.repository.MessageRepository;
import com.accenture.intern.docmind.repository.ViewAttachmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for managing chat sessions and message history.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SessionService {

    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final AttachmentRepository attachmentRepository;
    private final ViewAttachmentRepository viewAttachmentRepository;
    private final EmailService emailService;
    private final SessionCacheService sessionCacheService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final SessionSummaryService sessionSummaryService;
    private final AnalyticsService analyticsService;

    private static final String PDF_EXPORT_STREAM_KEY = "pdf_export_jobs";
    private static final String PDF_EXPORT_RESULT_PREFIX = "pdf_export_result:";
    private static final String PDF_EXPORT_BLOB_PREFIX = "pdf_export_blob:";

    public SessionResponse createSession(String userEmail, CreateSessionRequest request){
        User user=userRepository.findByEmail(userEmail);

        if(user==null) throw new RuntimeException("User Not Found 🚫");
        analyticsService.recordVisit(user.getId());

        LocalDateTime now=LocalDateTime.now();

        Session session=Session.builder()
                .user(user)
                .title(request.getTitle())
                .archived(false)
                .createdAt(now)
                .updatedAt(now)
                .build();

        Session savedSession=sessionRepository.save(session);
        return mapToResponse(savedSession);
    }

    public List<SessionResponse> getAllSessions(String userEmail){
        User user=userRepository.findByEmail(userEmail);

        if(user==null) throw new RuntimeException("User Not Found 🚫");
        analyticsService.recordVisit(user.getId());

        List<Session> sessions=sessionRepository.findByUser(user);

        List<SessionResponse> response=new ArrayList<>();
        for(Session s:sessions) response.add(mapToResponse(s));
        return response;
    }

    public SessionResponse getSessionById(String userEmail,Long sessionId){
        User user=userRepository.findByEmail(userEmail);

        if(user==null) throw new RuntimeException("User Not Found 🚫");
        analyticsService.recordVisit(user.getId());

        Session session=sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("No Session found 🚫"));

        if(!session.getUser().getId().equals(user.getId())){
            throw new RuntimeException("Access Denied !! You seems to be 👽");
        }
        SessionResponse response = mapToResponse(session);
        response.setMessages(getMessagesForSession(session));
        return response;
    }

    public void deleteSession(String userEmail, Long sessionId) {
        User user = userRepository.findByEmail(userEmail);
        if (user == null) throw new RuntimeException("User Not Found 🚫");

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session Not Found"));

        if (!session.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Access denied");
        }

        // 1. view_attachments are deleted automatically via DB-level ON DELETE CASCADE
        //    (see ViewAttachment.session mapping).

        // 2. Detach (do NOT delete) the attachments originally uploaded in this
        //    session. Attachment.session is a real FK column, so simply deleting
        //    the session while attachments still point at it would fail with a
        //    foreign-key violation — but the row itself must survive regardless:
        //    DocMind's corpus is a shared company knowledge base, and once a
        //    document is uploaded it stays searchable (and visible in the global
        //    Explore page) for everyone even after the session that introduced it
        //    is gone. Setting session -> null breaks the FK link cleanly without
        //    deleting the row, the Cloudinary file, or the indexed chunks.
        //    Optimized to use a bulk update instead of row-by-row JPA saves.
        attachmentRepository.detachAttachmentsFromSession(sessionId);

        // 3. Clear the in-memory upload/processing cache for this session so the
        //    frontend never sees a stale "processing" state after deletion.
        sessionCacheService.invalidateState(sessionId);

        // 4. Messages are deleted automatically via DB-level ON DELETE CASCADE
        //    (see Message.session mapping).

        // 5. Delete the session itself.
        sessionRepository.delete(session);
    }

    public SessionResponse renameSession(String userEmail, Long sessionId, String newTitle) {
        User user = userRepository.findByEmail(userEmail);
        if (user == null) {
            throw new RuntimeException("User Not Found 🚫");
        }

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("No Session found 🚫"));

        if (!session.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Access Denied !! You seems to be 👽");
        }

        session.setTitle(newTitle);
        session.setUpdatedAt(LocalDateTime.now());
        Session savedSession = sessionRepository.save(session);
        return mapToResponse(savedSession);
    }

    public PaginatedMessageResponse getSessionMessages(String userEmail, Long sessionId, Long cursor, int size) {
        User user = userRepository.findByEmail(userEmail);
        if(user == null) throw new RuntimeException("User Not Found 🚫");

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("No Session found 🚫"));

        if(!session.getUser().getId().equals(user.getId())){
            throw new RuntimeException("Access Denied !! You seems to be 👽");
        }
        
        List<Message> messages;
        if (cursor == null) {
            messages = messageRepository.findTop20BySessionOrderByMessageIdDesc(session);
        } else {
            messages = messageRepository.findTop20BySessionAndMessageIdLessThanOrderByMessageIdDesc(session, cursor);
        }
        
        boolean hasMore = messages.size() == 20; // If we got 20, there might be more. The size is fixed at 20 based on the repository method name.
        
        // The messages are returned DESC (newest first). 
        // We want to return them ASC (chronological) to the frontend.
        Collections.reverse(messages);
        
        List<MessageResponse> messageResponses = mapMessagesToResponses(messages);
        
        String nextCursor = null;
        if (hasMore && !messages.isEmpty()) {
            // After reversing, the oldest message in this batch is at index 0.
            nextCursor = String.valueOf(messages.get(0).getMessageId());
        }

        return PaginatedMessageResponse.builder()
                .messages(messageResponses)
                .hasMore(hasMore)
                .nextCursor(nextCursor)
                .build();
    }

    /**
     * Polled by the frontend after a document/Wikipedia upload to find out when
     * the 3 suggested starter questions are ready.
     */
    public SuggestedQuestionsResponse getSuggestedQuestions(String userEmail, Long sessionId) {
        User user = userRepository.findByEmail(userEmail);
        if (user == null) throw new RuntimeException("User Not Found 🚫");

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("No Session found 🚫"));

        if (!session.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Access Denied !! You seems to be 👽");
        }

        SessionUploadState state = sessionCacheService.getState(sessionId);
        if (state == null) {
            return SuggestedQuestionsResponse.builder()
                    .status(SessionUploadState.SuggestedQuestionsStatus.NOT_STARTED)
                    .questions(List.of())
                    .build();
        }

        return SuggestedQuestionsResponse.builder()
                .status(state.getQuestionsStatus())
                .questions(state.getSuggestedQuestions())
                .build();
    }

    private List<MessageResponse> getMessagesForSession(Session session) {
        List<Message> messages = messageRepository.findBySessionOrderByCreatedAtAsc(session);
        return mapMessagesToResponses(messages);
    }

    private List<MessageResponse> mapMessagesToResponses(List<Message> messages) {
        List<MessageResponse> messageResponses = new ArrayList<>();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (Message m : messages) {
            Object citations = null;
            if (m.getCitationsJson() != null && !m.getCitationsJson().isEmpty()) {
                try {
                    citations = mapper.readValue(m.getCitationsJson(), Object.class);
                } catch (Exception e) {
                    // Ignore parsing error and leave as null
                }
            }
            
            Object visuals = null;
            if (m.getVisualsJson() != null && !m.getVisualsJson().isEmpty()) {
                try {
                    visuals = mapper.readValue(m.getVisualsJson(), Object.class);
                } catch (Exception e) {
                    // Ignore parsing error and leave as null
                }
            }
            messageResponses.add(MessageResponse.builder()
                    .id(String.valueOf(m.getMessageId()))
                    .role(m.getRole())
                    .text(m.getContent())
                    .createdAt(m.getCreatedAt())
                    .citations(citations)
                    .visuals(visuals)
                    .build());
        }
        return messageResponses;
    }

    private SessionResponse mapToResponse(Session session) {
        return SessionResponse.builder()
                .sessionId(session.getSessionId())
                .title(session.getTitle())
                .archived(session.getArchived())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .build();
    }

    public byte[] exportSessionToMarkdown(String userEmail, Long sessionId) {
        User user = userRepository.findByEmail(userEmail);
        if (user == null) throw new RuntimeException("User Not Found 🚫");

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("No Session found 🚫"));

        if (!session.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Access Denied !! You seems to be 👽");
        }

        List<Message> messages = messageRepository.findBySessionOrderByCreatedAtAsc(session);
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(session.getTitle() != null ? session.getTitle() : "Chat Session").append("\n\n");

        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        sb.append("**Created:** ").append(session.getCreatedAt().format(formatter)).append("\n\n");
        sb.append("---\n\n");

        // Same LLM-generated executive summary PdfGeneratorService renders under
        // "Session summary" in the PDF export — kept here too so the two export
        // formats give equivalent content. SessionSummaryService.summarize()
        // never throws (it falls back to a generic line internally), so a slow
        // or failed LLM call degrades gracefully instead of breaking the export.
        String summary = sessionSummaryService.summarize(messages).block();
        sb.append("## Session Summary\n\n");
        sb.append(summary == null || summary.isBlank() ? "No summary available for this session." : summary).append("\n\n");
        sb.append("---\n\n");

        for (Message m : messages) {
            if (m.getContent() == null || m.getContent().isBlank()) continue;

            String role = m.getRole().name();
            if (role.equalsIgnoreCase("USER")) {
                if (m.getContent().startsWith("[file upload:") || m.getContent().startsWith("[wikipedia link:")) {
                    sb.append("📎 *").append(m.getContent()).append("*\n\n");
                } else {
                    sb.append("### You\n").append(m.getContent()).append("\n\n");
                }
            } else if (role.equalsIgnoreCase("ASSISTANT") || role.equalsIgnoreCase("MODEL")) {
                sb.append("### DocMind\n").append(m.getContent()).append("\n\n");
            } else {
                sb.append("### ").append(role).append("\n").append(m.getContent()).append("\n\n");
            }
        }

        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public void shareSessionViaEmail(String userEmail, Long sessionId, String targetEmail) {
        byte[] mdBytes = exportSessionToMarkdown(userEmail, sessionId);
        String markdown = new String(mdBytes, java.nio.charset.StandardCharsets.UTF_8);

        Parser parser = Parser.builder().build();
        org.commonmark.node.Node document = parser.parse(markdown);
        HtmlRenderer renderer = HtmlRenderer.builder().build();
        String htmlBody = renderer.render(document);

        Session session = sessionRepository.findById(sessionId).orElseThrow();
        String subject = "DocMind Chat Session: " + (session.getTitle() != null ? session.getTitle() : "Untitled");

        String fullHtml = "<html><body style='font-family: sans-serif; line-height: 1.6; max-width: 800px; margin: 0 auto;'>" +
                          htmlBody +
                          "</body></html>";

        emailService.sendHtmlEmail(targetEmail, subject, fullHtml);
    }

    public PdfExportJobResponse requestPdfExport(String userEmail, Long sessionId) {
        User user = userRepository.findByEmail(userEmail);
        if (user == null) throw new RuntimeException("User Not Found 🚫");

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("No Session found 🚫"));

        if (!session.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Access Denied !! You seems to be 👽");
        }

        String jobId = UUID.randomUUID().toString();

        Map<String, Object> recordMap = new HashMap<>();
        recordMap.put("jobId", jobId);
        recordMap.put("sessionId", sessionId.toString());
        recordMap.put("userEmail", userEmail);
        recordMap.put("timestamp", String.valueOf(System.currentTimeMillis()));

        redisTemplate.opsForStream().add(PDF_EXPORT_STREAM_KEY, recordMap);

        return PdfExportJobResponse.builder()
                .jobId(jobId)
                .status("QUEUED")
                .build();
    }

    public PdfExportStatusResponse getPdfExportStatus(String userEmail, Long sessionId, String jobId) {
        User user = userRepository.findByEmail(userEmail);
        if (user == null) throw new RuntimeException("User Not Found 🚫");

        Object raw = redisTemplate.opsForValue().get(PDF_EXPORT_RESULT_PREFIX + jobId);
        if (raw == null) {
            return PdfExportStatusResponse.builder().status("QUEUED").build();
        }

        try {
            String json = raw.toString();
            Map<?, ?> payload = objectMapper.readValue(json, Map.class);
            String status = (String) payload.get("status");
            return PdfExportStatusResponse.builder()
                    .status(status)
                    .downloadPath("READY".equals(status)
                            ? "/api/sessions/" + sessionId + "/export-pdf/" + jobId + "/download"
                            : null)
                    .fileName((String) payload.get("fileName"))
                    .errorMessage((String) payload.get("errorMessage"))
                    .build();
        } catch (Exception e) {
            return PdfExportStatusResponse.builder()
                    .status("FAILED")
                    .errorMessage("Could not read export status")
                    .build();
        }
    }

    /**
     * Reads the rendered PDF bytes for a completed export job back out of
     * Redis (no cloud storage involved — see PdfExportWorkerService). Returns
     * null if the job isn't ready/found/expired — the caller (SessionController)
     * maps that to a 404.
     */
    public byte[] downloadPdfExport(String userEmail, Long sessionId, String jobId) {
        User user = userRepository.findByEmail(userEmail);
        if (user == null) throw new RuntimeException("User Not Found 🚫");

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("No Session found 🚫"));

        if (!session.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Access Denied !! You seems to be 👽");
        }

        Object raw = redisTemplate.opsForValue().get(PDF_EXPORT_BLOB_PREFIX + jobId);
        if (raw == null) return null;

        return java.util.Base64.getDecoder().decode(raw.toString());
    }
}
