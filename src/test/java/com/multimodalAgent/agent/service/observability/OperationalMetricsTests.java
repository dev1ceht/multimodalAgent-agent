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
                "qdrant_vector",
                RetrievalStatus.FAILED,
                "Qdrant request timed out",
                2_000_000);

        assertThat(registry.get("multimodalagent.rag.retrieval")
                .tags("backend", "qdrant_vector", "status", "failed", "reason", "timeout")
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
    @Test
    void recordsAgentLifecycleMetricsWithOnlyBoundedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OperationalMetrics metrics = new OperationalMetrics(registry);

        metrics.recordAgentModelCall("started");
        metrics.recordAgentTool("search_knowledge", "success");
        metrics.recordAgentPolicyRejection("search_knowledge", "tool_not_allowed:student-input");
        metrics.recordAgentDuplicateSuppression("search_knowledge");
        metrics.recordAgentBudgetTermination("tool_call_budget_exceeded");
        metrics.recordAgentLatency("first_answer", 5_000_000);

        assertThat(registry.get("multimodalagent.agent.model.calls")
                .tag("outcome", "started").counter().count()).isOne();
        assertThat(registry.get("multimodalagent.agent.tool.calls")
                .tags("tool", "search_knowledge", "outcome", "success")
                .counter().count()).isOne();
        assertThat(registry.get("multimodalagent.agent.duplicate.suppressions")
                .tag("tool", "search_knowledge").counter().count()).isOne();
        assertThat(registry.get("multimodalagent.agent.policy.rejections")
                .tags("tool", "search_knowledge", "reason", "tool_not_allowed")
                .counter().count()).isOne();
        assertThat(registry.get("multimodalagent.agent.budget.terminations")
                .tag("reason", "tool_calls").counter().count()).isOne();
        assertThat(registry.get("multimodalagent.agent.latency")
                .tag("phase", "first_answer").timer().count()).isOne();
    }

    @Test
    void recordsContextBudgetAndSummaryMetricsWithBoundedDimensions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OperationalMetrics metrics = new OperationalMetrics(registry);

        metrics.recordContextBudget("summary", "compacted", 7600, 7000, 12);
        metrics.recordContextSummary("succeeded", "none", 8_000_000);
        metrics.recordContextSummaryCasConflict();
        metrics.updateContextSummaryQueueDepth(7);
        metrics.recordContextSummaryState(3, 42, 2, 5);
        metrics.recordContextToolEvidenceDropped(4);

        assertThat(registry.get("multimodalagent.context.budget.decisions")
                .tags("mode", "summary", "outcome", "compacted")
                .counter().count()).isOne();
        assertThat(registry.get("multimodalagent.context.input.tokens")
                .tags("mode", "summary", "outcome", "compacted")
                .summary().totalAmount()).isEqualTo(7600);
        assertThat(registry.get("multimodalagent.context.omitted.messages")
                .tag("mode", "summary").summary().totalAmount()).isEqualTo(12);
        assertThat(registry.get("multimodalagent.context.summary")
                .tags("outcome", "succeeded", "reason", "none")
                .timer().count()).isOne();
        assertThat(registry.get("multimodalagent.context.summary.cas.conflicts")
                .counter().count()).isOne();
        assertThat(registry.get("multimodalagent.context.summary.queue.depth")
                .gauge().value()).isEqualTo(7);
        assertThat(registry.get("multimodalagent.context.summary.version")
                .summary().totalAmount()).isEqualTo(3);
        assertThat(registry.get("multimodalagent.context.summary.covered.through")
                .summary().totalAmount()).isEqualTo(42);
        assertThat(registry.get("multimodalagent.context.summary.coverage.gap")
                .summary().totalAmount()).isEqualTo(2);
        assertThat(registry.get("multimodalagent.context.summary.lag.messages")
                .summary().totalAmount()).isEqualTo(5);
        assertThat(registry.get("multimodalagent.context.tool.evidence.dropped")
                .counter().count()).isEqualTo(4);
    }
}
