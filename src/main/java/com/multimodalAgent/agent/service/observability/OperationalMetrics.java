package com.multimodalAgent.agent.service.observability;

import com.multimodalAgent.agent.service.knowledge.retrieval.RetrievalStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Runtime metrics for operationally important workflow seams.
 *
 * <p>Only bounded values are used as tags. Raw exception messages, version keys, user input and
 * document names must never become metric dimensions.</p>
 */
@Component
public class OperationalMetrics {

    private final MeterRegistry registry;
    private final AtomicLong contextSummaryQueueDepth = new AtomicLong();

    public OperationalMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge(
                "multimodalagent.context.summary.queue.depth",
                contextSummaryQueueDepth,
                AtomicLong::doubleValue);
    }

    public void recordRetrieval(
            String backend,
            RetrievalStatus status,
            String reason,
            long elapsedNanos
    ) {
        Timer.builder("multimodalagent.rag.retrieval")
                .description("RAG retrieval latency by backend and bounded outcome")
                .tags(
                        "backend", boundedBackend(backend),
                        "status", status == null ? "unknown" : status.name().toLowerCase(Locale.ROOT),
                        "reason", reasonTag(reason))
                .register(registry)
                .record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    public void recordEvidenceQuality(boolean accepted) {
        Counter.builder("multimodalagent.rag.evidence.quality")
                .description("RAG evidence quality gate outcomes")
                .tag("accepted", Boolean.toString(accepted))
                .register(registry)
                .increment();
    }

    public void recordIndexTask(String outcome, String reason, long elapsedNanos) {
        Timer.builder("multimodalagent.knowledge.index")
                .description("Knowledge indexing task latency by bounded outcome")
                .tags("outcome", boundedOutcome(outcome), "reason", reasonTag(reason))
                .register(registry)
                .record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    public void recordKnowledgeUpload(String outcome, long elapsedNanos) {
        Timer.builder("multimodalagent.knowledge.upload")
                .description("Knowledge original storage latency by bounded outcome")
                .tag("outcome", boundedPipelineOutcome(outcome))
                .register(registry)
                .record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    public void recordKnowledgeStage(String stage, String outcome) {
        Counter.builder("multimodalagent.knowledge.stage")
                .description("Knowledge pipeline stage outcomes")
                .tags("stage", boundedStage(stage), "outcome", boundedPipelineOutcome(outcome))
                .register(registry)
                .increment();
    }

    public void recordKnowledgeDlt() {
        Counter.builder("multimodalagent.knowledge.dlt")
                .description("Knowledge events routed to dead letter handling")
                .register(registry)
                .increment();
    }

    public void recordHttpRequest(
            String method,
            String route,
            int status,
            long elapsedNanos
    ) {
        Timer.builder("multimodalagent.http.request")
                .description("HTTP request latency by normalized route")
                .tags(
                        "method", boundedMethod(method),
                        "route", boundedRoute(route),
                        "status", status <= 0 ? "unknown" : Integer.toString(status))
                .register(registry)
                .record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    public void recordAgentModelCall(String outcome) {
        Counter.builder("multimodalagent.agent.model.calls")
                .description("Agent model call outcomes with bounded dimensions")
                .tag("outcome", boundedAgentOutcome(outcome))
                .register(registry)
                .increment();
    }

    public void recordAgentTool(String toolName, String outcome) {
        Counter.builder("multimodalagent.agent.tool.calls")
                .description("Agent allowlisted tool call outcomes")
                .tags("tool", boundedTool(toolName), "outcome", boundedAgentOutcome(outcome))
                .register(registry)
                .increment();
    }

    public void recordAgentPolicyRejection(String toolName, String reason) {
        Counter.builder("multimodalagent.agent.policy.rejections")
                .description("Agent policy and authorization rejections")
                .tags("tool", boundedTool(toolName), "reason", boundedPolicyReason(reason))
                .register(registry)
                .increment();
    }

    public void recordAgentDuplicateSuppression(String toolName) {
        Counter.builder("multimodalagent.agent.duplicate.suppressions")
                .description("Agent tool calls suppressed by the duplicate-call budget")
                .tag("tool", boundedTool(toolName))
                .register(registry)
                .increment();
    }

    public void recordAgentBudgetTermination(String reason) {
        Counter.builder("multimodalagent.agent.budget.terminations")
                .description("Agent runs stopped by a bounded budget or deadline")
                .tag("reason", boundedBudgetReason(reason))
                .register(registry)
                .increment();
    }

    public void recordAgentLatency(String phase, long elapsedNanos) {
        Timer.builder("multimodalagent.agent.latency")
                .description("Agent run latency by bounded lifecycle phase")
                .tag("phase", boundedPhase(phase))
                .register(registry)
                .record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    public void recordContextBudget(
            String mode,
            String outcome,
            int estimatedTokens,
            int inputCeiling,
            int omittedMessages
    ) {
        String boundedMode = boundedContextMode(mode);
        String boundedResult = boundedContextBudgetOutcome(outcome);
        Counter.builder("multimodalagent.context.budget.decisions")
                .description("Context budget decisions by configured mode and bounded outcome")
                .tags("mode", boundedMode, "outcome", boundedResult)
                .register(registry)
                .increment();
        DistributionSummary.builder("multimodalagent.context.input.tokens")
                .description("Estimated input tokens after applying the context budget")
                .tags("mode", boundedMode, "outcome", boundedResult)
                .register(registry)
                .record(Math.max(0, estimatedTokens));
        DistributionSummary.builder("multimodalagent.context.input.ceiling")
                .description("Configured input-token ceiling observed by context budgeting")
                .tag("mode", boundedMode)
                .register(registry)
                .record(Math.max(0, inputCeiling));
        DistributionSummary.builder("multimodalagent.context.omitted.messages")
                .description("Messages omitted by a context budget decision")
                .tag("mode", boundedMode)
                .register(registry)
                .record(Math.max(0, omittedMessages));
    }

    public void recordContextSummary(String outcome, String reason, long elapsedNanos) {
        Timer.builder("multimodalagent.context.summary")
                .description("Rolling-summary worker latency by bounded outcome")
                .tags(
                        "outcome", boundedContextSummaryOutcome(outcome),
                        "reason", reasonTag(reason))
                .register(registry)
                .record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    public void recordContextSummaryCasConflict() {
        Counter.builder("multimodalagent.context.summary.cas.conflicts")
                .description("Rolling-summary compare-and-set conflicts")
                .register(registry)
                .increment();
    }

    public void updateContextSummaryQueueDepth(long depth) {
        contextSummaryQueueDepth.set(Math.max(0, depth));
    }

    public void recordContextSummaryState(
            long version,
            long coveredThrough,
            long coverageGap,
            long lagMessages
    ) {
        DistributionSummary.builder("multimodalagent.context.summary.version")
                .description("Committed rolling-summary versions")
                .register(registry)
                .record(Math.max(0, version));
        DistributionSummary.builder("multimodalagent.context.summary.covered.through")
                .description("Committed rolling-summary message watermark")
                .register(registry)
                .record(Math.max(0, coveredThrough));
        DistributionSummary.builder("multimodalagent.context.summary.coverage.gap")
                .description("Uncovered messages within the claimed compaction target")
                .register(registry)
                .record(Math.max(0, coverageGap));
        DistributionSummary.builder("multimodalagent.context.summary.lag.messages")
                .description("Committed messages still beyond the rolling-summary watermark")
                .register(registry)
                .record(Math.max(0, lagMessages));
    }

    public void recordContextToolEvidenceDropped(long count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("multimodalagent.context.tool.evidence.dropped")
                .description("Tool messages removed by final context fitting")
                .register(registry)
                .increment(count);
    }

    private String boundedContextMode(String value) {
        return switch (normalize(value)) {
            case "window", "budget", "summary" -> normalize(value);
            default -> "unknown";
        };
    }

    private String boundedContextBudgetOutcome(String value) {
        return switch (normalize(value)) {
            case "within_budget", "over_budget", "compacted", "rejected" -> normalize(value);
            default -> "unknown";
        };
    }

    private String boundedContextSummaryOutcome(String value) {
        return switch (normalize(value)) {
            case "succeeded", "retry_wait", "failed", "lease_lost", "skipped" -> normalize(value);
            default -> "unknown";
        };
    }

    private String boundedAgentOutcome(String value) {
        return switch (normalize(value)) {
            case "started", "success", "empty", "denied", "failed", "timeout", "cancelled" -> normalize(value);
            default -> "unknown";
        };
    }

    private String boundedTool(String value) {
        return switch (normalize(value)) {
            case "search_knowledge", "recall_memory", "get_support_status" -> normalize(value);
            default -> "unknown";
        };
    }

    private String boundedPolicyReason(String value) {
        String normalized = normalize(value);
        if (normalized.contains("consent")) {
            return "consent";
        }
        if (normalized.contains("schema") || normalized.contains("argument")) {
            return "schema";
        }
        if (normalized.contains("tool") || normalized.contains("allow")) {
            return "tool_not_allowed";
        }
        if (normalized.contains("memory") || normalized.contains("risk")) {
            return "risk_scope";
        }
        return "unknown";
    }

    private String boundedBudgetReason(String value) {
        String normalized = normalize(value);
        if (normalized.contains("deadline") || normalized.contains("timeout")) {
            return "deadline";
        }
        if (normalized.contains("model")) {
            return "model_calls";
        }
        if (normalized.contains("tool")) {
            return "tool_calls";
        }
        if (normalized.contains("identical")) {
            return "identical_tools";
        }
        return "other";
    }

    private String boundedPhase(String value) {
        return switch (normalize(value)) {
            case "prepare", "model_call", "tool_call", "first_status", "first_answer", "total" -> normalize(value);
            default -> "unknown";
        };
    }
    private String boundedBackend(String value) {
        return switch (normalize(value)) {
            case "qdrant_vector", "version_database_embedding",
                    "version_local_baseline", "legacy_local_baseline" ->
                    normalize(value);
            default -> "unknown";
        };
    }

    private String boundedOutcome(String value) {
        return switch (normalize(value)) {
            case "succeeded", "retry_wait", "failed", "lease_lost", "skipped" -> normalize(value);
            default -> "unknown";
        };
    }

    private String boundedPipelineOutcome(String value) {
        return switch (normalize(value)) {
            case "stored", "parsed", "indexed", "published", "queued", "duplicate",
                    "failed", "retry", "obsolete" -> normalize(value);
            default -> "unknown";
        };
    }

    private String boundedStage(String value) {
        return switch (normalize(value)) {
            case "upload", "outbox", "inbox", "parse", "index", "dlt" -> normalize(value);
            default -> "unknown";
        };
    }

    private String boundedMethod(String value) {
        return switch (normalize(value)) {
            case "get", "post", "put", "patch", "delete", "head", "options" -> normalize(value);
            default -> "unknown";
        };
    }

    private String boundedRoute(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String route = value.trim();
        if ("unmatched".equals(route)) {
            return route;
        }
        return route.startsWith("/") && route.length() <= 120 ? route : "unknown";
    }

    private String reasonTag(String value) {
        String reason = normalize(value);
        if (reason.isBlank() || "none".equals(reason)) {
            return "none";
        }
        if (containsAny(reason, "timeout", "timed out", "deadline")) {
            return "timeout";
        }
        if (containsAny(reason, "dimension", "invalid", "parse", "schema", "malformed")) {
            return "data_invalid";
        }
        if (containsAny(reason, "too_large", "token", "budget")) {
            return "data_invalid";
        }
        if (containsAny(reason, "cas", "conflict")) {
            return "conflict";
        }
        if (containsAny(reason, "requires", "configured", "disabled", "missing")) {
            return "configuration";
        }
        if (containsAny(
                reason,
                "unavailable",
                "failed",
                "qdrant",
                "embedding",
                "connection",
                "http",
                "ollama")) {
            return "dependency_unavailable";
        }
        return "unknown";
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
