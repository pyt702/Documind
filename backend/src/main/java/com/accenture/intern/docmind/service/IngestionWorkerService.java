package com.accenture.intern.docmind.service;

import com.accenture.intern.docmind.aiservices.embedding.DocumentParserService;
import com.accenture.intern.docmind.aiservices.embedding.EmbeddingService;
import com.accenture.intern.docmind.aiservices.embedding.WikipediaIngestionService;
import com.accenture.intern.docmind.aiservices.vision.ImageVisionResponse;
import com.accenture.intern.docmind.aiservices.vision.ImageVisionService;
import com.accenture.intern.docmind.dto.job.IngestionJobPayload;
import com.accenture.intern.docmind.entity.Job;
import com.accenture.intern.docmind.entity.JobStatus;
import com.accenture.intern.docmind.entity.SourceType;
import com.accenture.intern.docmind.repository.JobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import jakarta.annotation.PreDestroy;
import java.time.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class IngestionWorkerService {

    private final JobRepository jobRepository;
    private final DocumentParserService parserService;
    private final EmbeddingService embeddingService;
    private final ImageVisionService imageVisionService;
    private final WikipediaIngestionService wikipediaIngestionService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisConnectionFactory redisConnectionFactory;
    private final ObjectMapper objectMapper;
    private final CloudinaryService cloudinaryService;

    private StreamOperations<String, Object, Object> streamOps;
    private static final String CONSUMER_GROUP = "ingestion-workers";
    private final String consumerName = "worker-" + java.util.UUID.randomUUID().toString();
    private static final String STREAM_KEY = "ingestion_jobs";
    private volatile boolean running = true;
    private final java.util.concurrent.ExecutorService workerExecutor;

    private final com.accenture.intern.docmind.service.AnalyticsService analyticsService;
    private final TransactionTemplate transactionTemplate;

    public IngestionWorkerService(JobRepository jobRepository,
                                  DocumentParserService parserService,
                                  EmbeddingService embeddingService,
                                  ImageVisionService imageVisionService,
                                  WikipediaIngestionService wikipediaIngestionService,
                                  RedisTemplate<String, Object> redisTemplate,
                                  RedisConnectionFactory redisConnectionFactory,
                                  ObjectMapper objectMapper,
                                  CloudinaryService cloudinaryService,
                                  java.util.concurrent.ExecutorService workerExecutor,
                                  com.accenture.intern.docmind.service.AnalyticsService analyticsService,
                                  TransactionTemplate transactionTemplate) {
        this.jobRepository = jobRepository;
        this.parserService = parserService;
        this.embeddingService = embeddingService;
        this.imageVisionService = imageVisionService;
        this.wikipediaIngestionService = wikipediaIngestionService;
        this.redisTemplate = redisTemplate;
        this.redisConnectionFactory = redisConnectionFactory;
        this.objectMapper = objectMapper;
        this.cloudinaryService = cloudinaryService;
        this.workerExecutor = workerExecutor;
        this.analyticsService = analyticsService;
        this.transactionTemplate = transactionTemplate;
    }

    @PostConstruct
    public void init() {
        streamOps = redisTemplate.opsForStream();
        try {
            streamOps.createGroup(STREAM_KEY, CONSUMER_GROUP);
        } catch (Exception e) {
            log.info("Ingestion consumer group likely exists already");
        }
        workerExecutor.submit(this::runWorker);
    }

    @PreDestroy
    public void destroy() {
        running = false;
    }

    public void runWorker() {
        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records = streamOps.read(
                        Consumer.from(CONSUMER_GROUP, consumerName),
                        org.springframework.data.redis.connection.stream.StreamReadOptions.empty().block(Duration.ofSeconds(30)).count(1),
                        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                );

                if (records != null && !records.isEmpty()) {
                    for (MapRecord<String, Object, Object> record : records) {
                        processJob(record);
                    }
                } else {
                    // Try PEL if no new messages
                    records = streamOps.read(
                            Consumer.from(CONSUMER_GROUP, consumerName),
                            org.springframework.data.redis.connection.stream.StreamReadOptions.empty().count(10),
                            StreamOffset.create(STREAM_KEY, ReadOffset.from("0"))
                    );
                    if (records != null) {
                        for (MapRecord<String, Object, Object> record : records) {
                            processJob(record);
                        }
                    }
                }
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                    try {
                        streamOps.createGroup(STREAM_KEY, CONSUMER_GROUP);
                    } catch (Exception ignored) {
                    }
                } else {
                    log.error("Error polling ingestion jobs from Redis stream", e);
                }
            }
        }
    }

    private void processJob(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        Object payloadObj = value.get("payload");
        if (payloadObj == null) {
            log.warn("Missing payload field in ingestion job");
            streamOps.acknowledge(STREAM_KEY, CONSUMER_GROUP, record.getId());
            return;
        }

        IngestionJobPayload payload;
        try {
            if (payloadObj instanceof IngestionJobPayload) {
                payload = (IngestionJobPayload) payloadObj;
            } else if (payloadObj instanceof String) {
                payload = objectMapper.readValue((String) payloadObj, IngestionJobPayload.class);
            } else {
                payload = objectMapper.convertValue(payloadObj, IngestionJobPayload.class);
            }
        } catch (Exception e) {
            log.error("Failed to parse ingestion payload: " + payloadObj, e);
            streamOps.acknowledge(STREAM_KEY, CONSUMER_GROUP, record.getId());
            return;
        }

        String lockKey = "ingestion_lock:" + payload.getJobId();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 10, TimeUnit.MINUTES);
        if (Boolean.FALSE.equals(acquired)) {
            log.info("Ingestion Job {} already being processed", payload.getJobId());
            return;
        }

        Job job = jobRepository.findById(payload.getJobId()).orElse(null);
        if (job == null) {
            log.warn("Job {} not found in database", payload.getJobId());
            streamOps.acknowledge(STREAM_KEY, CONSUMER_GROUP, record.getId());
            return;
        }

        job.setStatus(JobStatus.PROCESSING);
        jobRepository.save(job);

        try {
            transactionTemplate.executeWithoutResult(status -> {
                try {
                    executeIngestion(payload, job);
                    
                    job.setStatus(JobStatus.COMPLETED);
                    job.setProgress(100);
                    jobRepository.save(job);
                    
                    int count = (payload.getSourceType() == SourceType.WIKIPEDIA) ? 1 : 1; 
                    analyticsService.incrementDocumentsUploaded(count);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            
            streamOps.acknowledge(STREAM_KEY, CONSUMER_GROUP, record.getId());
            log.info("Successfully completed ingestion job {}", job.getId());
        } catch (Exception e) {
            log.error("Failed to process ingestion job {}", payload.getJobId(), e);
            job.setStatus(JobStatus.FAILED);
            job.setError(e.getMessage());
            jobRepository.save(job);
            streamOps.acknowledge(STREAM_KEY, CONSUMER_GROUP, record.getId()); // ACK so it doesn't loop forever if poison pill
        } finally {
            // Cleanup local file if it's a file-based source
            if (payload.getSourceType() != SourceType.WIKIPEDIA && payload.getSourceLocation() != null) {
                try {
                    Files.deleteIfExists(Paths.get(payload.getSourceLocation()));
                } catch (IOException ignored) {}
            }
        }
    }

    /**
     * The standard random UUID (36 chars: 8-4-4-4-12 hex) followed by the "_"
     * separator that AttachmentService#uploadFile prepends when it writes the
     * uploaded file to disk (storedName = UUID.randomUUID() + "_" + originalName).
     * Stripped back off here so the name this worker stores as
     * DocumentChunk.sourceName matches the actual original filename — not the
     * on-disk storage name — since retrieval (HybridRetrievalService whole-
     * document mode, citations, Explore, etc.) all reason about the real
     * filename the user uploaded, never the UUID-prefixed storage name.
     */
    private static final java.util.regex.Pattern UUID_PREFIX =
            java.util.regex.Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}_");

    private void executeIngestion(IngestionJobPayload payload, Job job) throws Exception {
        String originalName = ""; // Can be passed in payload or derived
        Path dest = null;
        if (payload.getSourceType() != SourceType.WIKIPEDIA) {
            dest = Paths.get(payload.getSourceLocation());
            originalName = UUID_PREFIX.matcher(dest.getFileName().toString()).replaceFirst("");
        }

        List<Mono<Void>> ingestionMonos = new ArrayList<>();

        switch (payload.getSourceType()) {
            case PDF:
                DocumentParserService.PdfParseResult parsed = parserService.parsePdfWithImages(dest);
                if (parsed.text() != null && !parsed.text().isBlank()) {
                    ingestionMonos.add(embeddingService.processAndIngest(parsed.text(), parsed.elements(), "PDF", originalName, payload.getSourceUrl(), payload.getSessionId()));
                }
                
                int imgIndex = 0;
                for (DocumentParserService.ExtractedImage img : parsed.images()) {
                    imgIndex++;
                    com.accenture.intern.docmind.aiservices.vision.SemanticImage vr = img.visionResponse();
                    String tags = vr.keywords() != null ? String.join(",", vr.keywords()) : null;
                    String imageSourceName = originalName + " (page " + img.pageNumber() + " image)";
                    ingestionMonos.add(embeddingService.processAndIngest(
                            vr.toDenseEmbeddingText(), null, "PDF_IMAGE", imageSourceName, originalName, vr.imageType(), tags, payload.getSessionId(), null, payload.getSourceUrl()));
                }
                break;

            case TEXT:
            case MARKDOWN:
            case HTML:
                String parsedText = parserService.parseTextFile(dest);
                if (parsedText != null && !parsedText.isBlank()) {
                    ingestionMonos.add(embeddingService.processAndIngest(parsedText, null, payload.getSourceType().name(), originalName, payload.getSourceUrl(), payload.getSessionId()));
                }
                break;
                
            case WIKIPEDIA:
                originalName = payload.getSourceLocation();
                List<com.accenture.intern.docmind.dto.context.SemanticChunk> chunks = wikipediaIngestionService.fetchAndParse(originalName);
                if (chunks != null && !chunks.isEmpty()) {
                    String wikipediaUrl = "https://en.wikipedia.org/wiki/" + originalName.replace(" ", "_");
                    ingestionMonos.add(embeddingService.processAndIngestSemanticChunks(chunks, originalName, wikipediaUrl, payload.getSessionId()));
                } else {
                    throw new RuntimeException("Could not extract semantic chunks from Wikipedia page.");
                }
                break;

            case IMAGE:
                byte[] fileBytes = Files.readAllBytes(dest);
                String contentType = "image/jpeg";
                if (originalName.toLowerCase().endsWith(".png")) contentType = "image/png";
                else if (originalName.toLowerCase().endsWith(".webp")) contentType = "image/webp";
                
                com.accenture.intern.docmind.aiservices.vision.SemanticImage imageVision = imageVisionService.describeImage(
                        fileBytes, contentType, com.accenture.intern.docmind.aiservices.vision.ImageContextBuilder.buildStandaloneContext(originalName, null)).block();
                
                if (imageVision != null && imageVision.summary() != null && !imageVision.summary().isBlank()) {
                    String imgTags = imageVision.keywords() != null ? String.join(",", imageVision.keywords()) : null;
                    ingestionMonos.add(embeddingService.processAndIngest(
                            imageVision.toDenseEmbeddingText(), null, "IMAGE", originalName, originalName, imageVision.imageType(), imgTags, payload.getSessionId(), payload.getSourceUrl(), payload.getSourceUrl()));
                }
                break;

            default:
                break;
        }

        if (!ingestionMonos.isEmpty()) {
            Mono.when(ingestionMonos).block();
        }
    }
}
