package com.multimodalAgent.agent.service.agentruntime;

import com.multimodalAgent.agent.config.MindCareAgentProperties;
import com.multimodalAgent.agent.domain.AgentRunRecord;
import com.multimodalAgent.agent.domain.AgentToolExecutionRecord;
import com.multimodalAgent.agent.repository.AgentRunRepository;
import com.multimodalAgent.agent.repository.AgentToolExecutionRepository;
import com.multimodalAgent.agent.service.observability.OperationalMetrics;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists bounded run/tool metadata without user text, tool arguments, or raw tool results. */
@Service
public class AgentRunPersistenceService {

    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String ERROR = "ERROR";
    public static final String CANCELLED = "CANCELLED";
    public static final String INTERRUPTED = "INTERRUPTED";

    private final AgentRunRepository runRepository;
    private final AgentToolExecutionRepository toolRepository;
    private final OperationalMetrics operationalMetrics;
    private final MindCareAgentProperties agentProperties;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentRunPersistenceService(
            AgentRunRepository runRepository,
            AgentToolExecutionRepository toolRepository,
            OperationalMetrics operationalMetrics,
            MindCareAgentProperties agentProperties
    ) {
        this.runRepository = runRepository;
        this.toolRepository = toolRepository;
        this.operationalMetrics = operationalMetrics;
        this.agentProperties = agentProperties;
    }

    /** Compatibility constructor for focused unit tests. */
    public AgentRunPersistenceService(
            AgentRunRepository runRepository,
            AgentToolExecutionRepository toolRepository,
            OperationalMetrics operationalMetrics
    ) {
        this(runRepository, toolRepository, operationalMetrics, new MindCareAgentProperties());
    }

    /** Compatibility constructor for focused unit tests. */
    public AgentRunPersistenceService(
            AgentRunRepository runRepository,
            AgentToolExecutionRepository toolRepository
    ) {
        this(runRepository, toolRepository, null, new MindCareAgentProperties());
    }

    @Transactional
    public AgentRunRecord start(
            AgentRequest request,
            String executionMode,
            String model,
            String schemaVersion
    ) {
        Optional<AgentRunRecord> existing = runRepository.findById(request.runId());
        if (existing.isPresent()) {
            return existing.get();
        }
        AgentRunRecord record = new AgentRunRecord();
        record.setRunId(request.runId());
        record.setUserId(request.identity().userId());
        record.setSessionId(request.identity().sessionId());
        record.setSessionPublicId(request.identity().sessionPublicId());
        record.setExecutionMode(safe(executionMode, "legacy"));
        record.setModel(blankToNull(model));
        record.setSchemaVersion(safe(schemaVersion, "mindcare-agent-v1"));
        record.setRiskLevel(request.riskLevel() == null ? "NONE" : request.riskLevel().name());
        record.setNeedsRag(request.routing().needsRag());
        record.setStatus(RUNNING);
        record.setStartedAt(Instant.now());
        return runRepository.save(record);
    }

    @Transactional
    public void recordEvent(AgentEvent event) {
        if (event == null || event.runId() == null || event.runId().isBlank()) {
            return;
        }
        Optional<AgentRunRecord> maybeRun = runRepository.findById(event.runId());
        if (maybeRun.isEmpty()) {
            return;
        }
        AgentRunRecord run = maybeRun.get();
        if (isTerminal(run.getStatus())) {
            return;
        }
        Instant now = Instant.now();
        if (AgentEvent.STATUS.equals(event.type()) && run.getFirstStatusAt() == null) {
            run.setFirstStatusAt(now);
        }
        if (AgentEvent.TOKEN.equals(event.type()) && run.getFirstAnswerAt() == null) {
            run.setFirstAnswerAt(now);
        }
        if (AgentEvent.MODEL_CALL.equals(event.type())) {
            run.setModelCallCount(run.getModelCallCount() + 1);
            if (operationalMetrics != null) {
                operationalMetrics.recordAgentModelCall("started");
            }
        }
        if (AgentEvent.TOOL_START.equals(event.type())) {
            int ordinal = run.getToolCallCount() + 1;
            run.setToolCallCount(ordinal);
            AgentToolExecutionRecord tool = new AgentToolExecutionRecord();
            tool.setRunId(run.getRunId());
            tool.setToolCallId(safe(event.toolName(), "unknown") + "-" + ordinal);
            tool.setToolName(safe(event.toolName(), "unknown"));
            tool.setStatus("STARTED");
            tool.setStartedAt(now);
            tool.setPolicyEnforced(event.policyEnforced());
            toolRepository.save(tool);
        }
        if (AgentEvent.TOOL_RESULT.equals(event.type())) {
            String eventErrorCode = errorCode(event);
            toolRepository.findFirstByRunIdAndFinishedAtIsNullOrderByStartedAtDesc(event.runId())
                    .ifPresent(tool -> finishTool(tool, event, now));
            boolean rejected = isRejected(event);
            if (rejected) {
                run.setToolRejectionCount(run.getToolRejectionCount() + 1);
            }
            if (operationalMetrics != null) {
                operationalMetrics.recordAgentTool(event.toolName(), event.status());
                if (rejected) {
                    operationalMetrics.recordAgentPolicyRejection(event.toolName(), event.content());
                }
                if ("identical_tool_call_budget_exceeded".equals(eventErrorCode)) {
                    operationalMetrics.recordAgentDuplicateSuppression(event.toolName());
                }
            }
        }
        runRepository.save(run);
    }

    @Transactional
    public void finish(String runId, String status, String errorCode, boolean budgetTerminated) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        runRepository.findById(runId).ifPresent(run -> {
            if (isTerminal(run.getStatus())) {
                return;
            }
            run.setStatus(safe(status, ERROR));
            run.setFinishedAt(Instant.now());
            run.setBudgetTerminated(budgetTerminated || isBudgetCode(errorCode));
            run.setErrorCode(blankToNull(errorCode));
            runRepository.save(run);
            if (operationalMetrics != null) {
                if (run.isBudgetTerminated()) {
                    operationalMetrics.recordAgentBudgetTermination(errorCode);
                }
                recordLatency("total", run.getStartedAt(), run.getFinishedAt());
                recordLatency("first_status", run.getStartedAt(), run.getFirstStatusAt());
                recordLatency("first_answer", run.getStartedAt(), run.getFirstAnswerAt());
            }
        });
    }

    @Scheduled(fixedDelayString = "${multimodal-agent.agent.cleanup-interval-ms:3600000}")
    @Transactional
    public void cleanupExpiredMetadata() {
        Duration retention = agentProperties.getMetadataRetention();
        if (retention == null || retention.isZero() || retention.isNegative()) {
            retention = Duration.ofDays(7);
        }
        Instant cutoff = Instant.now().minus(retention);
        toolRepository.deleteByStartedAtBefore(cutoff);
        runRepository.deleteByFinishedAtBefore(cutoff);
    }

    @PostConstruct
    public void recoverInterruptedRuns() {
        markRunningInterrupted();
    }

    @Transactional
    public int markRunningInterrupted() {
        int count = 0;
        for (AgentRunRecord run : runRepository.findByStatus(RUNNING)) {
            run.setStatus(INTERRUPTED);
            run.setFinishedAt(Instant.now());
            run.setErrorCode("process_interrupted");
            runRepository.save(run);
            count++;
        }
        return count;
    }

    private void recordLatency(String phase, Instant startedAt, Instant finishedAt) {
        if (startedAt == null || finishedAt == null) {
            return;
        }
        operationalMetrics.recordAgentLatency(phase,
                java.time.Duration.between(startedAt, finishedAt).toNanos());
    }

    private void finishTool(
            AgentToolExecutionRecord tool,
            AgentEvent event,
            Instant finishedAt
    ) {
        tool.setFinishedAt(finishedAt);
        tool.setStatus(safe(event.status(), "UNKNOWN").toUpperCase());
        tool.setResultChars(Math.max(0, event.content() == null ? 0 : event.content().length()));
        tool.setPolicyEnforced(tool.isPolicyEnforced() || event.policyEnforced());
        tool.setErrorCode(errorCode(event));
        toolRepository.save(tool);
    }

    private boolean isRejected(AgentEvent event) {
        String status = event.status() == null ? "" : event.status().toLowerCase();
        return status.contains("denied") || status.contains("failed") || status.contains("error");
    }

    private String errorCode(AgentEvent event) {
        String content = event.content();
        int separator = content == null ? -1 : content.indexOf(':');
        return separator < 0 ? null : blankToNull(content.substring(separator + 1));
    }

    private boolean isBudgetCode(String value) {
        return value != null && (value.contains("budget") || value.contains("deadline"));
    }

    private boolean isTerminal(String status) {
        return SUCCESS.equals(status)
                || ERROR.equals(status)
                || CANCELLED.equals(status)
                || INTERRUPTED.equals(status);
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
