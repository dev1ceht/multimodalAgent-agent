package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.config.MindCareAgentProperties;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.MessageRole;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.dto.ChatStreamEvent;
import com.multimodalAgent.agent.service.PrivacySanitizer;
import com.multimodalAgent.agent.service.PsychologicalAssessmentService;
import com.multimodalAgent.agent.service.PsychologyAssessment;
import com.multimodalAgent.agent.service.agentruntime.AgentEvent;
import com.multimodalAgent.agent.service.agentruntime.AgentRequest;
import com.multimodalAgent.agent.service.agentruntime.AgentRunPersistenceService;
import com.multimodalAgent.agent.service.agentruntime.AgentSessionBusyException;
import com.multimodalAgent.agent.service.agentruntime.AgentSessionLeaseService;
import com.multimodalAgent.agent.service.agentruntime.MindCareAgentRuntime;
import com.multimodalAgent.agent.service.evaluation.EvaluationTraceService;
import com.multimodalAgent.agent.service.routing.RequestRouter;
import com.multimodalAgent.agent.service.routing.RoutingDecision;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Fixed business envelope around the SAA runtime.
 *
 * <p>This service owns conversation facts, risk floors and report side effects. ReactAgent owns
 * only the model/tool decision loop, so legacy preparation is not run a second time.</p>
 */
@Service
public final class AgentConversationService implements AgentConversationGateway {

    private final multimodalAgentProperties applicationProperties;
    private final MindCareAgentProperties agentProperties;
    private final ConversationMemory conversationMemory;
    private final ConversationHistoryMapper historyMapper;
    private final RequestRouter requestRouter;
    private final PsychologicalAssessmentService assessmentService;
    private final ReportLifecycle reportLifecycle;
    private final PrivacySanitizer privacySanitizer;
    private final EvaluationTraceService evaluationTraceService;
    private final MindCareAgentRuntime agentRuntime;
    private final AgentRunPersistenceService runPersistence;
    private final AgentSessionLeaseService sessionLease;

    @Autowired
    public AgentConversationService(
            multimodalAgentProperties applicationProperties,
            MindCareAgentProperties agentProperties,
            ConversationMemory conversationMemory,
            ConversationHistoryMapper historyMapper,
            RequestRouter requestRouter,
            PsychologicalAssessmentService assessmentService,
            ReportLifecycle reportLifecycle,
            PrivacySanitizer privacySanitizer,
            EvaluationTraceService evaluationTraceService,
            MindCareAgentRuntime agentRuntime,
            AgentRunPersistenceService runPersistence,
            AgentSessionLeaseService sessionLease
    ) {
        this.applicationProperties = applicationProperties;
        this.agentProperties = agentProperties;
        this.conversationMemory = conversationMemory;
        this.historyMapper = historyMapper;
        this.requestRouter = requestRouter;
        this.assessmentService = assessmentService;
        this.reportLifecycle = reportLifecycle;
        this.privacySanitizer = privacySanitizer;
        this.evaluationTraceService = evaluationTraceService;
        this.agentRuntime = agentRuntime;
        this.runPersistence = runPersistence;
        this.sessionLease = sessionLease;
    }

    /** Compatibility constructor for focused unit tests that do not need persistence or leases. */
    public AgentConversationService(
            multimodalAgentProperties applicationProperties,
            MindCareAgentProperties agentProperties,
            ConversationMemory conversationMemory,
            ConversationHistoryMapper historyMapper,
            RequestRouter requestRouter,
            PsychologicalAssessmentService assessmentService,
            ReportLifecycle reportLifecycle,
            PrivacySanitizer privacySanitizer,
            EvaluationTraceService evaluationTraceService,
            MindCareAgentRuntime agentRuntime
    ) {
        this(
                applicationProperties,
                agentProperties,
                conversationMemory,
                historyMapper,
                requestRouter,
                assessmentService,
                reportLifecycle,
                privacySanitizer,
                evaluationTraceService,
                agentRuntime,
                null,
                null);
    }

    public Flux<ServerSentEvent<ChatStreamEvent>> stream(ConversationRequest request) {
        return stream(request, true);
    }

    public Flux<ServerSentEvent<ChatStreamEvent>> stream(
            ConversationRequest request,
            boolean consentValidated
    ) {
        if (!consentValidated) {
            return Flux.just(
                    event("error", ChatStreamEvent.error(null, "当前请求未完成必要的授权确认。")),
                    event("done", ChatStreamEvent.done(null)));
        }
        return Mono.fromCallable(() -> prepare(request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(this::runPrepared)
                .onErrorResume(AgentSessionBusyException.class, ignored -> Flux.just(
                        event("error", ChatStreamEvent.error(null, "当前会话正在处理上一条请求，请稍后再试。")),
                        event("done", ChatStreamEvent.done(null))));
    }

    private PreparedAgentRun prepare(ConversationRequest request) {
        long preparationStarted = System.nanoTime();
        String input = request.input();
        String modelInput = privacySanitizer.sanitize(
                request.multimodalAnalysis() == null
                        || request.multimodalAnalysis().modelText() == null
                        || request.multimodalAnalysis().modelText().isBlank()
                        ? input
                        : request.multimodalAnalysis().modelText());
        Instant deadline = Instant.now().plus(positive(agentProperties.getTimeout(), Duration.ofSeconds(60)));
        String runId = UUID.randomUUID().toString();
        EvaluationTraceService.Trace trace = evaluationTraceService.start(
                request.request().evaluationId(), configuredModel(), modelInput);
        evaluationTraceService.bind(trace);
        ConversationIdentity identity = null;
        boolean leaseAcquired = false;
        try {
            identity = conversationMemory.open(
                    request.userId(),
                    request.request().sessionId(),
                    input);
            if (sessionLease != null && !sessionLease.tryAcquire(identity, runId, deadline)) {
                throw new AgentSessionBusyException();
            }
            leaseAcquired = sessionLease != null;
            ConversationHistory previousHistory = conversationMemory.recentHistory(identity);
            conversationMemory.append(identity, MessageRole.USER, input);
            if (request.multimodalAnalysis() != null) {
                conversationMemory.appendMultimodalMemory(identity, request.multimodalAnalysis());
            }

            RoutingDecision routing = requestRouter.decide(
                    modelInput,
                    historyMapper.toAiMessages(previousHistory),
                    externalRisk(request));
            PsychologyAssessment assessment = null;
            if (routing.riskLevel().ordinal() >= RiskLevel.MEDIUM.ordinal()) {
                assessment = request.multimodalAnalysis() == null
                        ? assessmentService.assess(modelInput, historyMapper.toAiMessages(previousHistory))
                        : request.multimodalAnalysis().fusedAssessment();
                if (assessment == null) {
                    assessment = assessmentService.assess(
                            modelInput,
                            historyMapper.toAiMessages(previousHistory));
                }
                RiskLevel finalRisk = higherRisk(routing.riskLevel(), assessment.risk());
                if (finalRisk.ordinal() > routing.riskLevel().ordinal()) {
                    routing = routing.withRiskFloor(finalRisk, "心理评估提高风险等级");
                }
                assessment = assessment.withRiskFloor(routing.riskLevel());
                if (routing.riskLevel().ordinal() >= RiskLevel.MEDIUM.ordinal()) {
                    reportLifecycle.persist(new ConversationReportDraft(
                            identity,
                            input,
                            routing.needsRag(),
                            assessment,
                            request.multimodalAnalysis()));
                }
            }
            routing = applyExternalRiskFloor(routing, externalRisk(request));
            evaluationTraceService.put(trace, "executionMode", "saa");
            evaluationTraceService.put(trace, "model", configuredModel());
            evaluationTraceService.put(trace, "schemaVersion", safeSchemaVersion());
            evaluationTraceService.put(trace, "runId", runId);
            evaluationTraceService.put(trace, "routingConfidence", routing.confidence());
            evaluationTraceService.duration(trace, "prepareMs", preparationStarted);
            evaluationTraceService.put(trace, "finalNeedsRag", routing.needsRag());
            evaluationTraceService.put(trace, "finalRisk", routing.riskLevel().name());
            AgentRequest agentRequest = new AgentRequest(
                    identity,
                    modelInput,
                    previousHistory,
                    request.multimodalAnalysis(),
                    routing,
                    runId,
                    deadline,
                    true);
            if (runPersistence != null) {
                runPersistence.start(agentRequest, "saa", configuredModel(),
                        safeSchemaVersion());
            }
            return new PreparedAgentRun(identity, agentRequest, trace);
        } catch (RuntimeException exception) {
            if (leaseAcquired && sessionLease != null) {
                sessionLease.release(identity, runId);
            }
            if (runPersistence != null) {
                runPersistence.finish(runId, AgentRunPersistenceService.ERROR, "prepare_failed", false);
            }
            evaluationTraceService.finish(
                    trace,
                    "error",
                    exception.getClass().getSimpleName() + ": " + exception.getMessage());
            throw exception;
        } finally {
            evaluationTraceService.unbind();
        }
    }

    private Flux<ServerSentEvent<ChatStreamEvent>> runPrepared(PreparedAgentRun prepared) {
        StreamState state = new StreamState(conversationMemory, evaluationTraceService, prepared.identity(), prepared.trace(),
                runPersistence, sessionLease, prepared.request().runId());
        return agentRuntime.run(prepared.request())
                .doOnNext(state::observe)
                .map(agentEvent -> toSse(agentEvent, prepared.identity().sessionPublicId()))
                .doOnError(state::fail)
                .doOnComplete(() -> state.finish(false))
                .doOnCancel(() -> state.finish(true))
                .doFinally(signal -> {
                    if (signal == reactor.core.publisher.SignalType.CANCEL) {
                        state.finish(true);
                    } else {
                        state.finish(false);
                    }
                });
    }

    private ServerSentEvent<ChatStreamEvent> toSse(AgentEvent agentEvent, String sessionId) {
        String type = agentEvent.type();
        ChatStreamEvent data = switch (type) {
            case AgentEvent.META -> ChatStreamEvent.meta(sessionId);
            case AgentEvent.TOKEN -> ChatStreamEvent.token(sessionId, agentEvent.content());
            case AgentEvent.ERROR -> ChatStreamEvent.error(sessionId, agentEvent.content());
            case AgentEvent.DONE -> ChatStreamEvent.done(sessionId);
            case AgentEvent.MODEL_CALL -> ChatStreamEvent.progress(AgentEvent.STATUS, sessionId, publicProgress(agentEvent));
            case AgentEvent.STATUS, AgentEvent.TOOL_START, AgentEvent.TOOL_RESULT ->
                    ChatStreamEvent.progress(type, sessionId, publicProgress(agentEvent));
            default -> ChatStreamEvent.progress("status", sessionId, "正在处理请求。");
        };
        return event(data.type(), data);
    }

    private String publicProgress(AgentEvent event) {
        if (AgentEvent.MODEL_CALL.equals(event.type())) {
            return "正在生成回复。";
        }
        if (AgentEvent.TOOL_START.equals(event.type())) {
            return "正在查询支持信息。";
        }
        if (AgentEvent.TOOL_RESULT.equals(event.type())) {
            return "查询步骤已完成。";
        }
        return event.content();
    }

    private RiskLevel externalRisk(ConversationRequest request) {
        return request.multimodalAnalysis() == null
                || request.multimodalAnalysis().fusedAssessment() == null
                ? RiskLevel.NONE
                : request.multimodalAnalysis().fusedAssessment().risk();
    }

    private RoutingDecision applyExternalRiskFloor(RoutingDecision routing, RiskLevel externalRisk) {
        if (externalRisk == null || externalRisk == RiskLevel.NONE) {
            return routing;
        }
        return routing.withRiskFloor(externalRisk, "多模态信号提高风险等级");
    }

    private RiskLevel higherRisk(RiskLevel left, RiskLevel right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    private String configuredModel() {
        if (agentProperties.getModel() != null && !agentProperties.getModel().isBlank()) {
            return agentProperties.getModel().trim();
        }
        return switch (applicationProperties.getAi().getProvider().toLowerCase()) {
            case "ollama" -> applicationProperties.getAi().getOllama().getModel();
            case "openai" -> applicationProperties.getAi().getOpenai().getModel();
            default -> applicationProperties.getAi().getProvider();
        };
    }

    private String safeSchemaVersion() {
        String value = agentProperties.getSchemaVersion();
        return value == null || value.isBlank() ? "mindcare-agent-v1" : value.trim();
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }

    private ServerSentEvent<ChatStreamEvent> event(String name, ChatStreamEvent data) {
        return ServerSentEvent.builder(data).event(name).build();
    }

    private static final class StreamState {
        private final ConversationMemory memory;
        private final EvaluationTraceService evaluationTraceService;
        private final ConversationIdentity identity;
        private final EvaluationTraceService.Trace trace;
        private final AgentRunPersistenceService runPersistence;
        private final AgentSessionLeaseService sessionLease;
        private final StringBuilder assistant = new StringBuilder();
        private final AtomicBoolean error = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean terminalDone = new AtomicBoolean();
        private int modelCalls;
        private int toolCalls;
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile String errorCode;

        private StreamState(
                ConversationMemory memory,
                EvaluationTraceService evaluationTraceService,
                ConversationIdentity identity,
                EvaluationTraceService.Trace trace,
                AgentRunPersistenceService runPersistence,
                AgentSessionLeaseService sessionLease,
                String runId
        ) {
            this.memory = memory;
            this.evaluationTraceService = evaluationTraceService;
            this.identity = identity;
            this.trace = trace;
            this.runPersistence = runPersistence;
            this.sessionLease = sessionLease;
            this.traceRunId = runId;
        }

        private void observe(AgentEvent event) {
            if (runPersistence != null) {
                try {
                    runPersistence.recordEvent(event);
                } catch (RuntimeException ignored) {
                    // Run metadata must not turn a student-facing stream into a second failure.
                }
            }
            if (AgentEvent.MODEL_CALL.equals(event.type())) {
                modelCalls++;
            } else if (AgentEvent.TOOL_START.equals(event.type())) {
                toolCalls++;
            }
            if (AgentEvent.TOKEN.equals(event.type())) {
                assistant.append(event.content());
            } else if (AgentEvent.ERROR.equals(event.type())) {
                error.set(true);
                errorCode = boundedErrorCode(event.status());
            } else if (AgentEvent.DONE.equals(event.type())) {
                terminalDone.set(true);
            }
        }

        private void fail(Throwable failure) {
            error.set(true);
            errorCode = boundedErrorCode(failure);
        }

        private void finish(boolean wasCancelled) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            if (wasCancelled) {
                cancelled.set(true);
                errorCode = "cancelled";
            }
            String status;
            if (cancelled.get()) {
                status = AgentRunPersistenceService.CANCELLED;
            } else if (error.get()) {
                status = AgentRunPersistenceService.ERROR;
            } else if (!terminalDone.get()) {
                status = AgentRunPersistenceService.ERROR;
                errorCode = "stream_incomplete";
            } else if (assistant.isEmpty()) {
                status = AgentRunPersistenceService.ERROR;
                errorCode = "empty_answer";
            } else {
                status = AgentRunPersistenceService.SUCCESS;
            }
            if (memory != null && AgentRunPersistenceService.SUCCESS.equals(status)) {
                memory.append(identity, MessageRole.ASSISTANT, assistant.toString());
            }
            if (runPersistence != null) {
                try {
                    runPersistence.finish(identity == null ? null : currentRunId(), status, errorCode,
                            isBudgetTermination(errorCode));
                } catch (RuntimeException ignored) {
                    // Metadata is best effort at stream finalization.
                }
            }
            if (sessionLease != null) {
                sessionLease.release(identity, currentRunId());
            }
            if (trace != null) {
                trace.put("outputChars", assistant.length());
                trace.put("modelCalls", modelCalls);
                trace.put("toolCalls", toolCalls);
                trace.put("terminalDone", terminalDone.get());
                evaluationTraceService.finish(trace, status.toLowerCase(), errorCode);
            }
        }

        private String currentRunId() {
            return traceRunId == null ? "" : traceRunId;
        }

        private String traceRunId;

        private static String boundedErrorCode(String value) {
            if (value == null || value.isBlank()) {
                return "agent_failed";
            }
            String normalized = value.trim().toLowerCase();
            return switch (normalized) {
                case "cancelled", "timeout", "deadline_exceeded", "model_call_budget_exceeded",
                        "tool_call_budget_exceeded", "identical_tool_call_budget_exceeded",
                        "argument_repair_budget_exceeded", "consent_required" -> normalized;
                default -> "agent_failed";
            };
        }

        private static String boundedErrorCode(Throwable failure) {
            Throwable current = failure;
            while (current != null) {
                if (current instanceof TimeoutException) {
                    return "timeout";
                }
                if (current instanceof CancellationException) {
                    return "cancelled";
                }
                current = current.getCause();
            }
            return "agent_failed";
        }

        private static boolean isBudgetTermination(String value) {
            return value != null && (value.contains("budget") || value.contains("deadline"));
        }
    }
    private record PreparedAgentRun(
            ConversationIdentity identity,
            AgentRequest request,
            EvaluationTraceService.Trace trace
    ) {
    }
}
