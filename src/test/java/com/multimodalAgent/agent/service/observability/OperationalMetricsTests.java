package com.multimodalAgent.agent.service.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.multimodalAgent.agent.service.knowledge.retrieval.RetrievalStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class OperationalMetricsTests {

    @Test
    void recordsRetrievalOutcomeWithBoundedFailureReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OperationalMetrics metrics = new OperationalMetrics(registry);

        metrics.recordRetrieval(
                "chroma",
                RetrievalStatus.FAILED,
                "Chroma request timed out",
                2_000_000);

        assertThat(registry.get("multimodalagent.rag.retrieval")
                .tags("backend", "chroma", "status", "failed", "reason", "timeout")
                .timer()
                .count())
                .isOne();
    }

    @Test
    void recordsQualityAndIndexOutcomesWithoutRawErrorMessagesAsTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OperationalMetrics metrics = new OperationalMetrics(registry);

        metrics.recordEvidenceQuality(false);
        metrics.recordIndexTask("retry_wait", "Embedding API unavailable for version v1", 3_000_000);

        assertThat(registry.get("multimodalagent.rag.evidence.quality")
                .tag("accepted", "false")
                .counter()
                .count())
                .isOne();
        assertThat(registry.get("multimodalagent.knowledge.index")
                .tags("outcome", "retry_wait", "reason", "dependency_unavailable")
                .timer()
                .count())
                .isOne();
    }

    @Test
    void keepsUnmatchedRouteAsAStableHttpMetricDimension() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OperationalMetrics metrics = new OperationalMetrics(registry);

        metrics.recordHttpRequest("GET", "unmatched", 200, 4_000_000);

        assertThat(registry.get("multimodalagent.http.request")
                .tag("method", "get")
                .tag("route", "unmatched")
                .tag("status", "200")
                .timer()
                .count()).isOne();
    }
}
