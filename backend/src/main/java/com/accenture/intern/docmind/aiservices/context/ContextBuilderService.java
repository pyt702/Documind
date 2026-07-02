package com.accenture.intern.docmind.aiservices.context;

import com.accenture.intern.docmind.config.RetrievalProperties;
import com.accenture.intern.docmind.dto.chat.ContextResult;
import com.accenture.intern.docmind.dto.chat.RetrievalTrace;
import com.accenture.intern.docmind.entity.Message;
import com.accenture.intern.docmind.entity.MessageRole;
import com.accenture.intern.docmind.repository.MessageRepository;
import com.accenture.intern.docmind.dto.context.SessionContext;
import com.accenture.intern.docmind.dto.context.DocumentReference;
import com.accenture.intern.docmind.aiservices.understanding.PlannerService;
import com.accenture.intern.docmind.aiservices.understanding.plan.ExecutionPlan;
import com.accenture.intern.docmind.aiservices.understanding.plan.DirectExecutionPlan;
import com.accenture.intern.docmind.aiservices.understanding.plan.StaticExecutionPlan;
import com.accenture.intern.docmind.aiservices.understanding.plan.AdaptiveExecutionPlan;
import com.accenture.intern.docmind.aiservices.understanding.Intent;
import com.accenture.intern.docmind.aiservices.understanding.RetrievalStrategy;
import com.accenture.intern.docmind.aiservices.understanding.Scope;
import com.accenture.intern.docmind.aiservices.understanding.RetrievalPlan;

import com.accenture.intern.docmind.aiservices.understanding.MergeOperation;
import com.accenture.intern.docmind.aiservices.retrieval.HybridRetrievalService;
import com.accenture.intern.docmind.aiservices.retrieval.RetrievalOrchestrator;
import com.accenture.intern.docmind.dto.retrieval.RetrievalCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ContextBuilderService {

    private static final int MAX_HISTORY_MESSAGES = 10;

    private final HybridRetrievalService hybridRetrievalService;
    private final MessageRepository messageRepository;
    private final PlannerService plannerService;
    private final RetrievalOrchestrator retrievalOrchestrator;
    private final com.accenture.intern.docmind.aiservices.understanding.plan.RetrievalController retrievalController;
    private final EvidenceStructuringService evidenceStructuringService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final String ragPrompt;
    private final String generalPrompt;

    public ContextBuilderService(
            HybridRetrievalService hybridRetrievalService,
            MessageRepository messageRepository,
            PlannerService plannerService,
            RetrievalOrchestrator retrievalOrchestrator,
            com.accenture.intern.docmind.aiservices.understanding.plan.RetrievalController retrievalController,
            EvidenceStructuringService evidenceStructuringService,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            @Value("classpath:prompts/ragprompt.st") Resource ragPromptResource,
            @Value("classpath:prompts/generalprompt.st") Resource generalPromptResource) throws IOException {
        this.hybridRetrievalService = hybridRetrievalService;
        this.messageRepository = messageRepository;
        this.plannerService = plannerService;
        this.retrievalOrchestrator = retrievalOrchestrator;
        this.retrievalController = retrievalController;
        this.evidenceStructuringService = evidenceStructuringService;
        this.objectMapper = objectMapper;
        this.ragPrompt = StreamUtils.copyToString(ragPromptResource.getInputStream(), StandardCharsets.UTF_8);
        this.generalPrompt = StreamUtils.copyToString(generalPromptResource.getInputStream(), StandardCharsets.UTF_8);
    }

    public Mono<ContextResult> buildContext(String question, Long sessionId, SessionContext sessionContext, boolean stillIndexing, reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {

        boolean skipSemanticMemory = true; 
        return fetchHistoryBlock(sessionId, question, skipSemanticMemory).flatMap(historyBlock -> {
            return plannerService.routeQuery(question, historyBlock, sessionContext, progressSink).flatMap(execPlan -> {
                
                RetrievalPlan decision = null;
                boolean decompositionRequired = false;
                MergeOperation mergeOperation = MergeOperation.NONE;
                List<RetrievalPlan> plans = Collections.emptyList();

                if (execPlan instanceof DirectExecutionPlan dir) {
                    decision = dir.retrievalPlan();
                    plans = List.of(decision);
                } else if (execPlan instanceof StaticExecutionPlan stat) {
                    decision = stat.plans().isEmpty() ? null : stat.plans().get(0);
                    decompositionRequired = true;
                    mergeOperation = stat.mergeOperation();
                    plans = stat.plans();
                } else if (execPlan instanceof AdaptiveExecutionPlan adapt) {
                    return orchestrateAdaptive(question, sessionId, adapt, Mono.just(historyBlock), sessionContext, progressSink);
                }

                if (decision != null && decision.scope() == Scope.NONE && (decision.purpose().contains("GREETING") || decision.purpose().contains("BOT_QA"))) {
                    return Mono.just(new ContextResult(generalPrompt, buildPrompt(question, "", historyBlock, sessionContext), Collections.emptyList(), 0.0));
                }

                if (decision != null && decision.scope() == Scope.NONE && (decision.purpose().contains("META_HISTORY") || decision.purpose().contains("CLARIFICATION"))) {
                    String metaContext = "Context: The user is asking about past conversations or asking for clarification. Rely on the Conversation History above.";
                    return Mono.just(new ContextResult(ragPrompt, buildPrompt(question, metaContext, historyBlock, sessionContext), Collections.emptyList(), 0.0));
                }

                if (decision != null && decision.scope() == Scope.NONE && decision.purpose().contains("SESSION_INFO")) {
                    List<String> activeDocs = sessionContext != null && sessionContext.uploadedDocuments() != null ? sessionContext.uploadedDocuments().stream().map(DocumentReference::filename).toList() : java.util.Collections.emptyList();
                    String activeDocsStr = activeDocs != null && !activeDocs.isEmpty()
                            ? String.join("\n- ", activeDocs)
                            : "No active documents in this session.";
                    String syntheticContext = "SESSION_INFO:\nActive session documents uploaded by the user:\n" + activeDocsStr;
                    return Mono.just(new ContextResult(ragPrompt, buildPrompt(question, syntheticContext, historyBlock, sessionContext), java.util.Collections.emptyList(), 1.0));
                }

                if (!decompositionRequired && decision != null && (decision.scope() == Scope.SPECIFIC_DOC || stillIndexing)) {
                    List<String> targetDocs = decision.targetDocuments();
                    if (targetDocs == null || targetDocs.isEmpty()) {
                        if (sessionContext != null && sessionContext.activeDocument() != null) {
                            targetDocs = List.of(sessionContext.activeDocument());
                        } else if (sessionContext != null && sessionContext.uploadedDocuments() != null && !sessionContext.uploadedDocuments().isEmpty()) {
                            targetDocs = List.of(sessionContext.uploadedDocuments().get(0).filename());
                        }
                    }
                    if (targetDocs != null && !targetDocs.isEmpty()) {
                        MergeOperation finalMergeOperation = mergeOperation;
                        RetrievalPlan finalDecision = decision;
                        return reactor.core.publisher.Flux.fromIterable(targetDocs)
                                .flatMap(docName -> hybridRetrievalService.wholeDocumentRetrieve(docName))
                                .reduce(new ArrayList<RetrievalCandidate>(), (acc, list) -> {
                                    acc.addAll(list);
                                    return acc;
                                })
                                .map(selectedDocs -> {
                                    com.accenture.intern.docmind.dto.chat.AggregatedEvidence agg = evidenceStructuringService.structure(selectedDocs, java.util.Collections.emptyList(), finalMergeOperation);
                                    return new ContextResult(
                                        getSystemPrompt(selectedDocs.isEmpty(), "", historyBlock, sessionContext),
                                        buildPrompt(question, agg.evidenceString(), historyBlock, sessionContext),
                                        selectedDocs, 1.0
                                    );
                                });
                    }
                }

                return orchestrateAndBuild(question, sessionId, execPlan, Mono.just(historyBlock), sessionContext, progressSink);
            });
        });
    }

    private Mono<ContextResult> orchestrateAdaptive(String question, Long sessionId, AdaptiveExecutionPlan adaptivePlan, Mono<String> historyBlockMono, SessionContext sessionContext, reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {
        RetrievalPlan initialPlan = new RetrievalPlan(
                "Adaptive Search",
                question,
                List.of(),
                com.accenture.intern.docmind.aiservices.understanding.RetrievalExecutionMode.RANKED_RETRIEVAL,
                Scope.CORPUS
        );
        RetrievalTrace trace = new RetrievalTrace();
        return adaptiveLoop(adaptivePlan, initialPlan, new ArrayList<>(), 1, sessionId, question, historyBlockMono, sessionContext, progressSink, trace);
    }

    /**
     * Forces the adaptive multi-iteration retrieval loop directly, bypassing
     * {@link PlannerService#routeQuery} entirely. Used by
     * {@link com.accenture.intern.docmind.aiservices.chat.LlmWorkerService} as
     * a fallback retry: if a normal (single-pass) answer comes back as the
     * "couldn't find relevant information" sentinel, we don't just give up —
     * we re-attempt retrieval using the same adaptive search-and-refine method
     * the planner reserves for hard queries, in case the simpler retrieval
     * strategy just didn't dig deep enough the first time.
     */
    public Mono<ContextResult> buildContextAdaptive(String question, Long sessionId, SessionContext sessionContext) {
        AdaptiveExecutionPlan adaptivePlan = new AdaptiveExecutionPlan(
                "Adaptive retry retrieval for: " + question,
                Collections.emptyList(),
                1,
                3,
                null,
                Collections.emptyList(),
                false
        );
        Mono<String> historyBlockMono = fetchHistoryBlock(sessionId, question, true);
        return orchestrateAdaptive(question, sessionId, adaptivePlan, historyBlockMono, sessionContext, null);
    }

    private Mono<ContextResult> adaptiveLoop(AdaptiveExecutionPlan adaptivePlan, RetrievalPlan currentPlan, List<RetrievalCandidate> accumulatedCandidates, int iteration, Long sessionId, String originalQuestion, Mono<String> historyBlockMono, SessionContext sessionContext, reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink, RetrievalTrace trace) {
        if (iteration > adaptivePlan.maxIterations()) {
            return buildFinalAdaptiveResult(originalQuestion, accumulatedCandidates, historyBlockMono, sessionContext, trace);
        }
        
        StaticExecutionPlan singleStatic = new StaticExecutionPlan(List.of(currentPlan), MergeOperation.UNION, Collections.emptyList(), false);
        return retrievalOrchestrator.orchestrate(originalQuestion, sessionId, singleStatic, progressSink)
            .flatMap(newResult -> {
                if (progressSink != null) {
                    String msg = new com.accenture.intern.docmind.dto.chat.ProgressEvent(
                            com.accenture.intern.docmind.dto.chat.ProgressStage.ADAPTIVE,
                            com.accenture.intern.docmind.dto.chat.ProgressStatus.INFO,
                            "Analyzing retrieved evidence...", iteration, adaptivePlan.maxIterations(), null
                    ).toJson(objectMapper);
                    progressSink.tryEmitNext(org.springframework.http.codec.ServerSentEvent.<String>builder(msg).event("progress").build());
                }

                com.accenture.intern.docmind.aiservices.understanding.plan.RetrievalObservation obs = retrievalOrchestrator.generateObservation(newResult.evidence(), currentPlan);
                trace.addObservation(obs);
                trace.addStep(String.format("Iteration %d: %s", iteration, obs.message()));
                accumulatedCandidates.addAll(newResult.evidence());
                
                return retrievalController.decideNextAction(adaptivePlan, obs)
                    .flatMap(action -> {
                        trace.addStep(String.format("Action chosen: %s. Reason: %s", action.type().name(), action.reasoning()));
                        if (action.type() == com.accenture.intern.docmind.aiservices.understanding.plan.AdaptiveAction.AdaptiveActionType.STOP || action.nextPlan().isEmpty()) {
                            return buildFinalAdaptiveResult(originalQuestion, accumulatedCandidates, historyBlockMono, sessionContext, trace);
                        } else {
                            if (progressSink != null) {
                                String targetStr = action.nextPlan().get().optimizedQuery();
                                String msg = new com.accenture.intern.docmind.dto.chat.ProgressEvent(
                                        com.accenture.intern.docmind.dto.chat.ProgressStage.RETRIEVAL,
                                        com.accenture.intern.docmind.dto.chat.ProgressStatus.RUNNING,
                                        "Searching for additional evidence about " + targetStr + "...", iteration, adaptivePlan.maxIterations(), null
                                ).toJson(objectMapper);
                                progressSink.tryEmitNext(org.springframework.http.codec.ServerSentEvent.<String>builder(msg).event("progress").build());
                            }
                            return adaptiveLoop(adaptivePlan, action.nextPlan().get(), accumulatedCandidates, iteration + 1, sessionId, originalQuestion, historyBlockMono, sessionContext, progressSink, trace);
                        }
                    });
            });
    }

    private Mono<ContextResult> buildFinalAdaptiveResult(String question, List<RetrievalCandidate> candidates, Mono<String> historyBlockMono, SessionContext sessionContext, RetrievalTrace trace) {
        return historyBlockMono.flatMap(historyBlock -> {
             trace.addStep(String.format("Adaptive Loop finished, returning %d chunks.", candidates.size()));
             double topScore = candidates.isEmpty() ? 0.0 : candidates.get(0).finalScore();
             
             com.accenture.intern.docmind.dto.chat.AggregatedEvidence agg = evidenceStructuringService.structure(candidates, Collections.emptyList(), MergeOperation.UNION);
             String augmentedPrompt = buildPrompt(question, agg.evidenceString(), historyBlock, sessionContext);
             return Mono.just(new ContextResult(
                     getSystemPrompt(candidates.isEmpty(), "", historyBlock, sessionContext),
                     augmentedPrompt, candidates, candidates, Collections.emptyList(), Collections.emptyList(), agg.updatedVisuals(), trace, topScore));
        });
    }

    private Mono<ContextResult> orchestrateAndBuild(String question, Long sessionId, ExecutionPlan execPlan, Mono<String> historyBlockMono, SessionContext sessionContext, reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {
        return retrievalOrchestrator.orchestrate(question, sessionId, execPlan, progressSink)
                .zipWith(historyBlockMono)
                .flatMap(tuple -> {
                    com.accenture.intern.docmind.dto.chat.RetrievalResult result = tuple.getT1();
                    String historyBlock = tuple.getT2();

                    RetrievalTrace trace = new RetrievalTrace();
                    trace.addStep(String.format("Orchestrator returned %d merged chunks.", result.evidence().size()));

                    double topScore = result.evidence().isEmpty() ? 0.0 : result.evidence().get(0).finalScore();
                    
                    String primaryStrategy = "";

                    com.accenture.intern.docmind.dto.chat.AggregatedEvidence agg = evidenceStructuringService.structure(result.evidence(), result.visuals(), execPlan.getMergeOperation());
                    String augmentedPrompt = buildPrompt(question, agg.evidenceString(), historyBlock, sessionContext);
                    
                    return Mono.just(new ContextResult(
                            getSystemPrompt(result.evidence().isEmpty(), primaryStrategy, historyBlock, sessionContext),
                            augmentedPrompt, result.evidence(), result.evidence(), Collections.emptyList(), Collections.emptyList(), agg.updatedVisuals(), trace, topScore));
                });
    }

    private Mono<String> fetchHistoryBlock(Long sessionId, String query, boolean skipSemanticMemory) {
        return Mono.fromCallable(() -> {
                    List<Message> history = messageRepository.findTop10BySession_SessionIdOrderByCreatedAtDesc(sessionId);
                    List<Message> limitedHistory = history.stream()
                            .limit(MAX_HISTORY_MESSAGES)
                            .toList();
                    List<Message> reversedHistory = new ArrayList<>(limitedHistory);
                    Collections.reverse(reversedHistory);

                    StringBuilder historyBuilder = new StringBuilder();
                    for (Message msg : reversedHistory) {
                        String prefix = msg.getRole() == MessageRole.USER ? "User" : "Assistant";
                        historyBuilder.append(prefix).append(": ").append(msg.getContent()).append("\n");
                    }
                    return historyBuilder.toString().trim();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("Failed to load conversation history for session {}: {}", sessionId, e.getMessage());
                    return Mono.just("");
                });
    }



    private String buildPrompt(String question, String contextBlock, String historyBlock, SessionContext sessionContext) {
        String sessionContextStr = "";
        if (sessionContext != null && sessionContext.uploadedDocuments() != null && !sessionContext.uploadedDocuments().isEmpty()) {
            List<String> docs = sessionContext.uploadedDocuments().stream().map(DocumentReference::filename).toList();
            sessionContextStr = "Runtime Session Context:\nUploaded Files:\n- " + String.join("\n- ", docs) + "\n";
            if (sessionContext.activeDocument() != null) {
                sessionContextStr += "Currently Active File in UI: " + sessionContext.activeDocument() + "\n";
            }
        }
        
        return "Context Information:\n" + contextBlock + "\n\n" + sessionContextStr + "\nConversation History:\n" + historyBlock + "\n\nUser Question:\n" + question;
    }

    private String getSystemPrompt(boolean isEmpty, String strategy, String historyBlock, SessionContext sessionContext) {
        if (isEmpty && (historyBlock == null || historyBlock.isBlank())) return generalPrompt;

        String directive;
        if ("CONCEPT_EXPANSION".equals(strategy)) {
            directive = "Never say a concept is absent because the exact word is missing. " +
                    "Infer the concept from the underlying evidence present in the retrieved context. " +
                    "Map thematic evidence to the abstract concept the user asked about and answer accordingly.";
        } else if ("META_DOC_SEARCH".equals(strategy)) {
            directive = "Your task is to identify WHICH documents are relevant to the user's query and name them explicitly. " +
                    "CRITICAL RULE: Do NOT require the exact query word or phrase to appear verbatim in the document. " +
                    "Instead, identify the underlying theme or concept in the user's question, then evaluate each document's content " +
                    "to determine if it thematically relates to that concept — even if different vocabulary is used. " +
                    "For example, if asked about 'leadership', a document about 'commanding troops' or 'guiding a team' qualifies. " +
                    "If asked about 'innovation', a document describing 'a new invention' or 'a novel solution' qualifies. " +
                    "Name every file whose content meaningfully relates to the queried theme and briefly explain why.";
        } else {
            directive = "Be literal. Extrapolate nothing. If the exact phrase or fact is missing, state that it is not present.";
        }

        return ragPrompt.replace("<inferenceDirective>", directive);
    }
}
