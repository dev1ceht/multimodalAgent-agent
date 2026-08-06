package com.multimodalAgent.agent.service.knowledge.retrieval;

import com.multimodalAgent.agent.service.knowledge.SearchResult;
import java.util.List;
import java.util.Objects;

/**
 * 检索模块的完整结果。空证据和后端故障由 status 明确区分。
 */
public record RetrievalResult(
        RetrievalStatus status,
        String backend,
        List<SearchResult> evidence,
        String reason
) {

    public RetrievalResult {
        status = Objects.requireNonNull(status, "status");
        backend = backend == null || backend.isBlank() ? "unknown" : backend.trim();
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        reason = reason == null ? "" : reason.trim();
    }

    public static RetrievalResult ready(String backend, List<SearchResult> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return empty(backend, "检索完成，但没有找到相关证据。");
        }
        return new RetrievalResult(RetrievalStatus.READY, backend, evidence, "");
    }

    public static RetrievalResult empty(String backend, String reason) {
        return new RetrievalResult(RetrievalStatus.EMPTY, backend, List.of(), reason);
    }

    public static RetrievalResult degraded(String backend, List<SearchResult> evidence, String reason) {
        return new RetrievalResult(RetrievalStatus.DEGRADED, backend, evidence, reason);
    }

    public static RetrievalResult failed(String backend, String reason) {
        return new RetrievalResult(RetrievalStatus.FAILED, backend, List.of(), reason);
    }
}
