package com.multimodalAgent.agent.service.observability;

import com.multimodalAgent.agent.service.knowledge.retrieval.RetrievalStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
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

    public OperationalMetrics(MeterRegistry registry) {
        this.registry = registry;
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

    private String boundedBackend(String value) {
        return switch (normalize(value)) {
            case "chroma", "version_database_embedding", "version_local_baseline", "legacy_local_baseline" ->
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
        if (reason.isBlank()) {
            return "none";
        }
        if (containsAny(reason, "timeout", "timed out", "deadline")) {
            return "timeout";
        }
        if (containsAny(reason, "dimension", "invalid", "parse", "schema", "malformed")) {
            return "data_invalid";
        }
        if (containsAny(reason, "requires", "configured", "disabled", "missing")) {
            return "configuration";
        }
        if (containsAny(
                reason,
                "unavailable",
                "failed",
                "chroma",
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
