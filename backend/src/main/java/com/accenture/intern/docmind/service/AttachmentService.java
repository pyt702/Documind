package com.accenture.intern.docmind.service;

import com.accenture.intern.docmind.dto.attachment.AttachmentDeleteResponse;
import com.accenture.intern.docmind.dto.attachment.AttachmentResponse;
import com.accenture.intern.docmind.dto.attachment.AttachmentUploadResult;
import com.accenture.intern.docmind.entity.*;
import com.accenture.intern.docmind.repository.AttachmentRepository;
import com.accenture.intern.docmind.repository.DocumentChunkRepository;
import com.accenture.intern.docmind.repository.MessageRepository;
import com.accenture.intern.docmind.repository.SessionRepository;
import com.accenture.intern.docmind.repository.UserRepository;
import com.accenture.intern.docmind.aiservices.retrieval.VectorStoreService;
import lombok.extern.slf4j.Slf4j;
import com.accenture.intern.docmind.aiservices.embedding.DocumentParserService;
import com.accenture.intern.docmind.aiservices.embedding.EmbeddingService;
import com.accenture.intern.docmind.aiservices.embedding.WikipediaIngestionService;
import com.accenture.intern.docmind.aiservices.vision.ImageVisionService;
import com.accenture.intern.docmind.aiservices.vision.SemanticImage;
import com.accenture.intern.docmind.dto.job.IngestionJobPayload;
import com.accenture.intern.docmind.entity.Job;
import com.accenture.intern.docmind.entity.JobStatus;
import com.accenture.intern.docmind.entity.JobType;
import com.accenture.intern.docmind.entity.SourceType;
import com.accenture.intern.docmind.repository.JobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
public class AttachmentService {

    private final AttachmentRepository attachmentRepository;
    private final com.accenture.intern.docmind.repository.ViewAttachmentRepository viewAttachmentRepository;
    private final MessageRepository messageRepository;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final VectorStoreService vectorStoreService;
    private final DocumentParserService parserService;
    private final EmbeddingService embeddingService;
    private final ImageVisionService imageVisionService;
    private final CloudinaryService cloudinaryService;
    private final WikipediaIngestionService wikipediaIngestionService;
    private final JobRepository jobRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Self-reference to THIS bean's Spring proxy (not the raw `this`).
     * Needed because deleteExploreAttachment() calls the @Transactional
     * deleteExploreAttachmentBlocking() from inside the same class — a
     * plain `this.deleteExploreAttachmentBlocking(...)` call bypasses the
     * proxy entirely, so @Transactional would silently never apply (Spring's
     * well-known "self-invocation" pitfall). Calling through `self` instead
     * routes back through the proxy, so the transaction interceptor runs and
     * an EntityManager gets bound to whichever thread actually executes it.
     * @Lazy breaks the circular self-dependency this would otherwise create.
     */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private AttachmentService self;

    /** Root storage dir — always resolved to absolute path at startup */
    @Value("${app.storage.root:storage}")
    private String storageRoot;

    private Path absoluteStorageRoot;

    public AttachmentService(AttachmentRepository attachmentRepository,
                             com.accenture.intern.docmind.repository.ViewAttachmentRepository viewAttachmentRepository,
                             MessageRepository messageRepository,
                             SessionRepository sessionRepository,
                             UserRepository userRepository,
                             DocumentChunkRepository documentChunkRepository,
                             VectorStoreService vectorStoreService,
                             DocumentParserService parserService,
                             EmbeddingService embeddingService,
                             ImageVisionService imageVisionService,
                             CloudinaryService cloudinaryService,
                             WikipediaIngestionService wikipediaIngestionService,
                             JobRepository jobRepository,
                             RedisTemplate<String, Object> redisTemplate,
                             ObjectMapper objectMapper) {
        this.attachmentRepository = attachmentRepository;
        this.viewAttachmentRepository = viewAttachmentRepository;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.vectorStoreService = vectorStoreService;
        this.parserService = parserService;
        this.embeddingService = embeddingService;
        this.imageVisionService = imageVisionService;
        this.cloudinaryService = cloudinaryService;
        this.wikipediaIngestionService = wikipediaIngestionService;
        this.jobRepository = jobRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @jakarta.annotation.PostConstruct
    public void init() throws IOException {
        // Resolve relative path (e.g. "storage") to absolute based on CWD (backend/)
        absoluteStorageRoot = Paths.get(storageRoot).toAbsolutePath().normalize();
        Files.createDirectories(absoluteStorageRoot);
        System.out.println("📁 Storage root: " + absoluteStorageRoot);
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Saves the file to the correct sub-folder under storageRoot,
     * then persists an Attachment row linked to a system Message in the given session.
     */
    public Mono<AttachmentUploadResult> uploadFile(Long sessionId, String userEmail, FilePart filePart) {
        return Mono.fromCallable(() -> {
            // 1. Verify session belongs to caller
            Session session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new RuntimeException("Session not found"));

            if (!session.getUser().getEmail().equals(userEmail)) {
                throw new RuntimeException("Access denied");
            }

            // 2. Determine type + subfolder from content-type
            String contentType = filePart.headers().getContentType() != null
                    ? filePart.headers().getContentType().toString()
                    : "application/octet-stream";

            AttachmentType type = resolveType(contentType);
            String subFolder = resolveFolder(type);

            // 3. Build destination path using the resolved absolute root
            Path dir = absoluteStorageRoot.resolve(subFolder);
            Files.createDirectories(dir);

            String originalName = filePart.filename();
            String storedName = UUID.randomUUID() + "_" + originalName;
            Path dest = dir.resolve(storedName);

            // 4. Write file to disk temporarily for parsing (blocking, hence fromCallable + boundedElastic)
            filePart.transferTo(dest).block();

            byte[] fileBytes = Files.readAllBytes(dest);

            String storagePath = null;
            String publicUrl;
            String cloudinaryPublicId = null;
            String cloudinaryResourceType = null;

            // For PDF/IMAGE we can know up front (via parsed text or vision
            // description) whether this exact content already exists somewhere
            // in the corpus. If so, reuse its existing Cloudinary URL instead of
            // uploading another copy of the same bytes — this is the whole point
            // of doing parse/vision BEFORE the Cloudinary call rather than after.
            // OTHER files have no extractable text to hash, so they always upload
            // (no dedup signal available for them).
            //
            // Wrapped in its own try/catch with fail-open semantics: if parsing/
            // vision blows up here, we fall straight through to the unconditional
            // Cloudinary upload below exactly as before this change, rather than
            // aborting the whole request — a dedup check is an optimization, not
            // something that should be able to block an upload from succeeding.
            DocumentParserService.PdfParseResult pdfParsed = null;
            SemanticImage imageVision = null;
            String existingSourceUrl = null;

            try {
                if (type == AttachmentType.PDF) {
                    pdfParsed = parserService.parsePdfWithImages(dest);
                    if (pdfParsed.text() != null && !pdfParsed.text().isBlank()) {
                        existingSourceUrl = embeddingService.findExistingSourceUrl(pdfParsed.text())
                                .block().orElse(null);
                    }
                } else if (type == AttachmentType.IMAGE) {
                    String context = com.accenture.intern.docmind.aiservices.vision.ImageContextBuilder.buildStandaloneContext(originalName, null);
                    imageVision = imageVisionService.describeImage(fileBytes, contentType, context).block();
                    if (imageVision != null && imageVision.summary() != null && !imageVision.summary().isBlank()) {
                        existingSourceUrl = embeddingService.findExistingSourceUrl(imageVision.toDenseEmbeddingText())
                                .block().orElse(null);
                    }
                }
            } catch (Exception e) {
                log.warn("Pre-upload parse/vision failed for '{}', will upload to Cloudinary and retry parsing below: {}", originalName, e.getMessage());
                pdfParsed = null;
                imageVision = null;
                existingSourceUrl = null;
            }

            if (existingSourceUrl != null) {
                log.info("'{}' matches content already in Cloudinary ({}) — skipping re-upload", originalName, existingSourceUrl);
                publicUrl = existingSourceUrl;
                // cloudinaryPublicId/cloudinaryResourceType stay null: this Attachment
                // row doesn't own a distinct Cloudinary asset, so there's nothing
                // for THIS row to delete later — the original upload's row does.
            } else if (type == AttachmentType.PDF) {
                CloudinaryService.UploadResult uploaded =
                        cloudinaryService.uploadRaw(fileBytes, "storage/pdfs", originalName);
                publicUrl = uploaded.url();
                cloudinaryPublicId = uploaded.publicId();
                cloudinaryResourceType = "raw";
            } else if (type == AttachmentType.IMAGE) {
                CloudinaryService.UploadResult uploaded =
                        cloudinaryService.uploadImage(fileBytes, "storage/images", originalName);
                publicUrl = uploaded.url();
                cloudinaryPublicId = uploaded.publicId();
                cloudinaryResourceType = "image";
            } else {
                CloudinaryService.UploadResult uploaded =
                        cloudinaryService.uploadRaw(fileBytes, "storage/others", originalName);
                publicUrl = uploaded.url();
                cloudinaryPublicId = uploaded.publicId();
                cloudinaryResourceType = "raw";
            }

            List<Mono<Void>> ingestionMonos = new ArrayList<>();
            try {
                if (type == AttachmentType.PDF) {
                    // Reuse the parse from the pre-Cloudinary dedup check above when
                    // available; if that check was skipped/failed, parse now exactly
                    // as the original code always did.
                    DocumentParserService.PdfParseResult parsed = pdfParsed != null
                            ? pdfParsed
                            : parserService.parsePdfWithImages(dest);

                    if (parsed.text() != null && !parsed.text().isBlank()) {
                        ingestionMonos.add(
                                embeddingService.processAndIngest(parsed.text(), parsed.elements(), type.name(), originalName, publicUrl, sessionId));
                    }

                    int imgIndex = 0;
                    for (DocumentParserService.ExtractedImage img : parsed.images()) {
                        imgIndex++;
                        String extractedImageUrl = saveExtractedPdfImage(img, originalName, imgIndex);
                        if (extractedImageUrl == null) {
                            continue; // failed to persist this one image — skip, don't fail the whole upload
                        }
                        String imageSourceName = originalName + " (page " + img.pageNumber() + " image)";
                        SemanticImage vr = img.visionResponse();
                        String tags = vr.keywords() != null ? String.join(",", vr.keywords()) : null;
                        
                        ingestionMonos.add(embeddingService.processAndIngest(
                                vr.toDenseEmbeddingText(), null, "PDF_IMAGE", imageSourceName, originalName, vr.imageType(), tags, sessionId, extractedImageUrl, publicUrl));
                    }

                    log.info("Successfully processed '{}' ({} text chunk-source, {} embedded images)",
                            originalName, parsed.text() == null || parsed.text().isBlank() ? 0 : 1, parsed.images().size());

                } else if (type == AttachmentType.TEXT) {
                    String parsedText = parserService.parseTextFile(dest);
                    if (parsedText != null && !parsedText.isBlank()) {
                        ingestionMonos.add(embeddingService.processAndIngest(parsedText, null, type.name(), originalName, publicUrl, sessionId));
                        log.info("Successfully processed '{}'", originalName);
                    }
                } else if (type == AttachmentType.IMAGE) {
                    // Reuse the vision result from the pre-Cloudinary dedup check
                    // above when available; if that check was skipped/failed, run
                    // vision now exactly as the original code always did.
                    SemanticImage parsedVision = imageVision != null
                            ? imageVision
                            : imageVisionService.describeImage(fileBytes, contentType, com.accenture.intern.docmind.aiservices.vision.ImageContextBuilder.buildStandaloneContext(originalName, null)).block();
                    if (parsedVision != null && parsedVision.summary() != null && !parsedVision.summary().isBlank()) {
                        String tags = parsedVision.keywords() != null ? String.join(",", parsedVision.keywords()) : null;
                        ingestionMonos.add(
                                embeddingService.processAndIngest(parsedVision.toDenseEmbeddingText(), null, type.name(), originalName, originalName, parsedVision.imageType(), tags, sessionId, publicUrl, publicUrl));
                        log.info("Successfully processed '{}'", originalName);
                    }
                }
            } catch (Exception e) {
                log.error("Failed pre-upload parse for '{}': {}", originalName, e.getMessage());
            }

            // Create background Job for ingestion
            Job job = Job.builder()
                    .type(JobType.DOCUMENT_INGESTION)
                    .status(JobStatus.QUEUED)
                    .build();
            jobRepository.save(job);

            SourceType sourceTypeEnum = switch (type) {
                case PDF -> SourceType.PDF;
                case IMAGE -> SourceType.IMAGE;
                case TEXT -> SourceType.TEXT;
                default -> SourceType.OTHER;
            };

            IngestionJobPayload payload = IngestionJobPayload.builder()
                    .jobId(job.getId())
                    .sourceType(sourceTypeEnum)
                    .sourceLocation(dest.toAbsolutePath().toString())
                    .sessionId(sessionId)
                    .userId(session.getUser().getId())
                    .sourceUrl(publicUrl)
                    .build();

            // Push to Redis Stream
            try {
                redisTemplate.opsForStream().add("ingestion_jobs", Map.of("payload", payload));
            } catch (Exception e) {
                log.error("Failed to serialize job payload for PDF", e);
            }

            Mono<Void> ingestionMono = Mono.empty(); // Now handled async via Redis

            long sizeBytes;
            try {
                sizeBytes = Files.size(dest);
            } catch (IOException e) {
                sizeBytes = -1L;
            }

            // 5. Create a system Message so the upload shows up in chat history/transcript
            Message systemMsg = Message.builder()
                    .session(session)
                    .role(MessageRole.USER)
                    .content("[file upload: " + originalName + "]")
                    .status(MessageStatus.COMPLETE)
                    .createdAt(LocalDateTime.now())
                    .build();
            messageRepository.save(systemMsg);

            // 6. Persist the Attachment record, linked directly to the session
            // it was uploaded in (provenance) — this row is part of DocMind's
            // shared, persistent corpus and survives even if that session is
            // later deleted (see SessionService#deleteSession).
            //
            // If THIS SAME USER already has an Attachment row for this exact
            // content (existingSourceUrl != null AND they're the owner of that
            // row), reuse it via a new ViewAttachment link instead of inserting
            // a duplicate row for bytes they've already uploaded before.
            //
            // If the duplicate content was originally uploaded by a DIFFERENT
            // user, don't reuse their row — insert a brand-new Attachment row
            // for THIS user instead (still pointing at the same Cloudinary
            // asset/publicUrl, so nothing gets re-uploaded). This keeps
            // Explore's "who owns this file" accounting correct: every user
            // who has a copy of a file gets their own deletable row, and
            // AttachmentService#deleteExploreAttachment can tell, from how many
            // rows share a url, whether it's safe to purge the underlying
            // Cloudinary asset + document_chunks + Pinecone vectors, or whether
            // it should just detach this one user's row.
            Attachment attachment = existingSourceUrl != null
                    ? attachmentRepository.findFirstByUrlAndUserIdOrderByUploadedAtAsc(existingSourceUrl, session.getUser().getId()).orElse(null)
                    : null;

            if (attachment != null) {
                log.info("'{}' duplicates existing attachment #{} owned by this same user — reusing it instead of inserting a new row",
                        originalName, attachment.getAttachmentId());
            } else {
                attachment = Attachment.builder()
                        .session(session)
                        .userId(session.getUser().getId())
                        .type(type)
                        .fileName(originalName)
                        .storagePath(storagePath)
                        .url(publicUrl)
                        .cloudinaryPublicId(cloudinaryPublicId)
                        .cloudinaryResourceType(cloudinaryResourceType)
                        .mimeType(contentType)
                        .fileSizeBytes(sizeBytes)
                        .uploadedAt(LocalDateTime.now())
                        .build();
                attachmentRepository.save(attachment);
            }

            // 7. Record this session's membership in the "View Attachments" list.
            // Unlike the Attachment row above, this join row IS deleted when the
            // session is deleted (see ViewAttachmentRepository).
            viewAttachmentRepository.save(ViewAttachment.builder()
                    .session(session)
                    .attachment(attachment)
                    .addedAt(LocalDateTime.now())
                    .build());

            return new AttachmentUploadResult(toResponse(attachment), ingestionMono);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Persists a single image extracted from inside a PDF to
     * {@code storage/images/extracted/} and returns its public {@code /files/...}
     * URL, so a citation referencing that image's chunk can render it. Returns
     * null (rather than throwing) on failure, so one bad image never fails the
     * whole PDF's ingestion.
     */
    private String saveExtractedPdfImage(DocumentParserService.ExtractedImage img, String originalPdfName, int imgIndex) {
        try {
            Path dir = absoluteStorageRoot.resolve("images").resolve("extracted");
            Files.createDirectories(dir);

            String ext = img.mimeType() != null && img.mimeType().contains("png") ? "png" : "jpg";
            String baseName = originalPdfName.replaceAll("[^a-zA-Z0-9.-]", "_");
            String storedName = UUID.randomUUID() + "_" + baseName + "_p" + img.pageNumber() + "_" + imgIndex + "." + ext;
            Path dest = dir.resolve(storedName);

            Files.write(dest, img.imageBytes());

            String storagePath = "images/extracted/" + storedName;
            return "/files/" + storagePath;
        } catch (IOException e) {
            log.error("Failed to persist extracted PDF image (page {}): {}", img.pageNumber(), e.getMessage());
            return null;
        }
    }

    public Mono<AttachmentUploadResult> uploadWikipedia(Long sessionId, String userEmail, String url) {
        return Mono.fromCallable(() -> {
            // 1. Verify session belongs to caller
            Session session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new RuntimeException("Session not found"));

            if (!session.getUser().getEmail().equals(userEmail)) {
                throw new RuntimeException("Access denied");
            }

            String originalName;
            String wikipediaUrl = url;
            if (url.startsWith("http")) {
                // Extract title from URL (e.g. https://en.wikipedia.org/wiki/Spider-Man -> Spider-Man)
                String pageTitle = url.substring(url.lastIndexOf("/") + 1);
                originalName = java.net.URLDecoder.decode(pageTitle, java.nio.charset.StandardCharsets.UTF_8);
            } else {
                originalName = url; // Selected directly by user from frontend search
                wikipediaUrl = "https://en.wikipedia.org/wiki/" + originalName.replace(" ", "_");
            }

            // We don't fetch chunks here anymore, just queue the job.

            // Create background Job for ingestion
            Job job = Job.builder()
                    .type(JobType.DOCUMENT_INGESTION)
                    .status(JobStatus.QUEUED)
                    .build();
            jobRepository.save(job);

            IngestionJobPayload payload = IngestionJobPayload.builder()
                    .jobId(job.getId())
                    .sourceType(SourceType.WIKIPEDIA)
                    .sourceLocation(originalName) // For wikipedia, location is the title/url
                    .sessionId(sessionId)
                    .userId(session.getUser().getId())
                    .build();

            // Push to Redis Stream
            try {
                redisTemplate.opsForStream().add("ingestion_jobs", Map.of("payload", payload));
            } catch (Exception e) {
                log.error("Failed to serialize job payload for Wikipedia", e);
            }

            Mono<Void> ingestionMono = Mono.empty(); // Handled async in worker

            // 5. Create a system Message so the link shows up in chat history/transcript
            Message systemMsg = Message.builder()
                    .session(session)
                    .role(MessageRole.USER)
                    .content("[wikipedia link: " + originalName + "]")
                    .status(MessageStatus.COMPLETE)
                    .createdAt(LocalDateTime.now())
                    .build();
            messageRepository.save(systemMsg);

            long sizeBytes = 0L;

            // 6. Persist the Attachment record, linked directly to the session
            // it was added in (provenance) — survives even if that session is
            // later deleted (see SessionService#deleteSession).
            Attachment attachment = Attachment.builder()
                    .session(session)
                    .userId(session.getUser().getId())
                    .type(AttachmentType.WIKIPEDIA)
                    .fileName(originalName)
                    .storagePath(wikipediaUrl)
                    .url(wikipediaUrl)
                    .mimeType("text/html")
                    .fileSizeBytes(sizeBytes)
                    .uploadedAt(LocalDateTime.now())
                    .build();
            attachmentRepository.save(attachment);

            // 7. Record this session's membership in the "View Attachments" list.
            viewAttachmentRepository.save(ViewAttachment.builder()
                    .session(session)
                    .attachment(attachment)
                    .addedAt(LocalDateTime.now())
                    .build());

            return new AttachmentUploadResult(toResponse(attachment), ingestionMono);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AttachmentResponse> getSessionAttachments(Long sessionId, String userEmail) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));

        if (!session.getUser().getEmail().equals(userEmail)) {
            throw new RuntimeException("Access denied");
        }

        return viewAttachmentRepository.findAttachmentsBySessionId(sessionId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> getAllGlobalAttachments(String userEmail) {
        User user = userRepository.findByEmail(userEmail);
        if (user == null) {
            throw new RuntimeException("User not found");
        }
        return attachmentRepository.findByUserIdOrderByUploadedAtDesc(user.getId()).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Deletes a single attachment from the caller's Explore view, with
     * duplicate-ownership-aware cleanup:
     * <p>
     * 1. Looks up every Attachment row (across all users) sharing the same
     *    url. If exactly one row exists (this user is the sole owner), the
     *    file is hard-deleted everywhere: the Cloudinary asset, every
     *    document_chunks row whose sourceUrl matches, and the corresponding
     *    Pinecone vectors (looked up by DocumentChunk.vectorId).
     * 2. If more than one row shares that url (other users also have this
     *    content), only THIS user's own Attachment row (and its
     *    ViewAttachment membership rows) is removed — the shared Cloudinary
     *    asset, chunks, and vectors are left alone since other users still
     *    depend on them.
     */
    public Mono<AttachmentDeleteResponse> deleteExploreAttachment(Long attachmentId, String userEmail) {
        return Mono.fromCallable(() -> self.deleteExploreAttachmentBlocking(attachmentId, userEmail))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Transactional
    public AttachmentDeleteResponse deleteExploreAttachmentBlocking(Long attachmentId, String userEmail) {
        User user = userRepository.findByEmail(userEmail);
        if (user == null) {
            throw new RuntimeException("User not found");
        }

        Attachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new RuntimeException("Attachment not found"));

        if (attachment.getUserId() == null || !attachment.getUserId().equals(user.getId())) {
            throw new RuntimeException("Access denied");
        }

        String url = attachment.getUrl();
        List<Attachment> owners = url != null ? attachmentRepository.findByUrl(url) : List.of(attachment);
        int ownerCount = owners.isEmpty() ? 1 : owners.size();

        if (ownerCount <= 1) {
            // Sole owner — purge everywhere.
            String cloudinaryPublicId = attachment.getCloudinaryPublicId();
            String cloudinaryResourceType = attachment.getCloudinaryResourceType();
            if (cloudinaryPublicId == null) {
                // This row's own asset fields can be null (e.g. it was created via the
                // dedup-reuse path) even though it's the sole remaining owner — fall back
                // to scanning the other owner rows (should just be itself here, but this
                // stays correct even if that assumption ever changes).
                for (Attachment a : owners) {
                    if (a.getCloudinaryPublicId() != null) {
                        cloudinaryPublicId = a.getCloudinaryPublicId();
                        cloudinaryResourceType = a.getCloudinaryResourceType();
                        break;
                    }
                }
            }
            if (cloudinaryPublicId != null) {
                cloudinaryService.deleteFile(cloudinaryPublicId, cloudinaryResourceType);
            }

            if (url != null) {
                List<DocumentChunk> chunks = documentChunkRepository.findBySourceUrl(url);
                if (!chunks.isEmpty()) {
                    List<String> vectorIds = chunks.stream()
                            .map(DocumentChunk::getVectorId)
                            .filter(java.util.Objects::nonNull)
                            .collect(Collectors.toList());
                    // Safe to block here: this method only ever runs on a
                    // boundedElastic thread (see deleteExploreAttachment above),
                    // never directly on the Netty/event-loop thread.
                    vectorStoreService.deleteByIds(vectorIds).block();
                    documentChunkRepository.deleteAll(chunks);
                }
            }

            viewAttachmentRepository.deleteByAttachment_AttachmentId(attachmentId);
            attachmentRepository.delete(attachment);

            log.info("Attachment #{} ('{}') fully deleted — was sole owner", attachmentId, attachment.getFileName());
            return AttachmentDeleteResponse.builder()
                    .attachmentId(attachmentId)
                    .fullyDeleted(true)
                    .ownerCount(ownerCount)
                    .message("File removed for everyone — no other user had it.")
                    .build();
        } else {
            // Other users still reference this content — only detach this user's copy.
            viewAttachmentRepository.deleteByAttachment_AttachmentId(attachmentId);
            attachmentRepository.delete(attachment);

            log.info("Attachment #{} ('{}') detached — {} other user(s) still reference this file",
                    attachmentId, attachment.getFileName(), ownerCount - 1);
            return AttachmentDeleteResponse.builder()
                    .attachmentId(attachmentId)
                    .fullyDeleted(false)
                    .ownerCount(ownerCount)
                    .message("Removed from your Explore list. Kept — " + (ownerCount - 1) + " other user(s) still have this file.")
                    .build();
        }
    }

    public Mono<List<String>> searchWikipedia(String query) {
        return parserService.searchWikipedia(query);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AttachmentType resolveType(String contentType) {
        String ct = contentType.toLowerCase();
        if (ct.contains("pdf"))                                      return AttachmentType.PDF;
        if (ct.startsWith("image/"))                                 return AttachmentType.IMAGE;
        if (ct.contains("text/") || ct.contains("markdown"))        return AttachmentType.TEXT;
        return AttachmentType.OTHER;
    }

    private String resolveFolder(AttachmentType type) {
        return switch (type) {
            case PDF   -> "pdfs";
            case IMAGE -> "images";
            case TEXT  -> "texts";
            default    -> "other";
        };
    }

    private AttachmentResponse toResponse(Attachment a) {
        return AttachmentResponse.builder()
                .attachmentId(a.getAttachmentId())
                .sessionId(a.getSession() != null ? a.getSession().getSessionId() : null)
                .userId(a.getUserId())
                .type(a.getType())
                .fileName(a.getFileName())
                .storagePath(a.getStoragePath())
                .url(a.getUrl())
                .mimeType(a.getMimeType())
                .fileSizeBytes(a.getFileSizeBytes())
                .uploadedAt(a.getUploadedAt())
                .build();
    }
}

