package com.multimodalAgent.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.service.knowledge.EvidenceProvenance;
import com.multimodalAgent.agent.service.ai.AiClient;
import com.multimodalAgent.agent.service.evaluation.EvaluationTraceService;
import com.multimodalAgent.agent.service.knowledge.retrieval.EvidenceRetriever;
import com.multimodalAgent.agent.service.knowledge.retrieval.RetrievalQuery;
import com.multimodalAgent.agent.service.knowledge.retrieval.RetrievalResult;
import com.multimodalAgent.agent.service.knowledge.retrieval.RetrievalStatus;
import com.multimodalAgent.agent.service.observability.OperationalMetrics;
import java.util.List;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgenticRagServiceTests {

    @Mock
    private EvidenceRetriever evidenceRetriever;

    @Mock
    private AiClient aiClient;

    @Mock
    private EvaluationTraceService evaluationTraceService;

    @Mock
    private OperationalMetrics operationalMetrics;

    private multimodalAgentProperties properties;
    private AgenticRagService service;

    @BeforeEach
    void setUp() {
        properties = new multimodalAgentProperties();
        properties.getKnowledge().setTopK(4);
        service = new AgenticRagService(
                evidenceRetriever,
                properties,
                aiClient,
                new ObjectMapper(),
                evaluationTraceService,
                new EvidenceQualityPolicy(properties),
                operationalMetrics);
    }

    @Test
    void rewritesToOneQueryAndDoesNotFollowUpWhenReviewFindsMissingCoverage() {
        when(aiClient.completeJson(anyList(), anyMap()))
                .thenReturn(
                        "{\"query\":\"睡眠困难 应对建议\"}",
                        "{\"sufficient\":false,\"reason\":\"missing safety conditions\"}");
        when(evidenceRetriever.retrieve(any()))
                .thenReturn(RetrievalResult.ready(
                        "fake",
                        List.of(new SearchResult(null, "sleep.md", "sleep support", 0.9))));

        AgenticRagResult result = service.retrieve("I need sleep support", List.of());

        assertThat(result.sufficient()).isFalse();
        assertThat(result.retrievalStatus()).isEqualTo(RetrievalStatus.READY);
        assertThat(result.queries()).containsExactly("睡眠困难 应对建议");
        assertThat(result.review()).contains("missing safety conditions");
        ArgumentCaptor<RetrievalQuery> query = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(evidenceRetriever, times(1)).retrieve(query.capture());
        assertThat(query.getValue().text()).isEqualTo("睡眠困难 应对建议");
        verify(aiClient, times(2)).completeJson(anyList(), anyMap());
    }

    @Test
    void rewriteFailureFallsBackToOriginalQuestionAndRetrievesOnce() {
        when(aiClient.completeJson(anyList(), anyMap()))
                .thenReturn(
                        "Here is the rewritten query: {\"query\":\"wrong query\"}",
                        "{\"sufficient\":true,\"reason\":\"grounded\"}");
        when(evidenceRetriever.retrieve(any()))
                .thenReturn(RetrievalResult.ready(
                        "fake",
                        List.of(new SearchResult(null, "sleep.md", "sleep support", 0.9))));

        AgenticRagResult result = service.retrieve("I need sleep support", List.of());

        assertThat(result.sufficient()).isTrue();
        assertThat(result.queryRewrite()).isEqualTo("I need sleep support");
        assertThat(result.queries()).containsExactly("I need sleep support");
        verify(evidenceRetriever, times(1)).retrieve(any());
        verify(aiClient, times(2)).completeJson(anyList(), anyMap());
    }

    @Test
    void retrievalFailureIsVisibleToTheCaller() {
        when(aiClient.completeJson(anyList(), anyMap()))
                .thenReturn("{\"query\":\"sleep support\"}");
        when(evidenceRetriever.retrieve(any()))
                .thenReturn(RetrievalResult.failed("elasticsearch_rrf", "Elasticsearch unavailable"));

        AgenticRagResult result = service.retrieve("I need sleep support", List.of());

        assertThat(result.sufficient()).isFalse();
        assertThat(result.retrievalStatus()).isEqualTo(RetrievalStatus.FAILED);
        assertThat(result.contextBlock()).contains("知识库当前不可用");
        verify(aiClient, times(1)).completeJson(anyList(), anyMap());
    }

    @Test
    void minimumEvidenceQualityGateRejectsWeakEvidence() {
        when(aiClient.completeJson(anyList(), anyMap()))
                .thenReturn(
                        "{\"query\":\"sleep support\"}",
                        "{\"sufficient\":true,\"reason\":\"looks relevant\"}");
        when(evidenceRetriever.retrieve(any()))
                .thenReturn(RetrievalResult.ready(
                        "fake",
                        List.of(new SearchResult(null, "sleep.md", "Generic content.", 0.05))));

        AgenticRagResult result = service.retrieve("I need sleep support", List.of());

        assertThat(result.sufficient()).isFalse();
        assertThat(result.evidence()).isEmpty();
        assertThat(result.review()).contains("相关性不足");
        verify(evaluationTraceService).put("ragEvidenceQualityAccepted", false);
        verify(operationalMetrics).recordEvidenceQuality(false);
    }

    @Test
    void evaluationTraceIncludesStableEvidenceIdentityAndProvenance() {
        when(aiClient.completeJson(anyList(), anyMap()))
                .thenReturn(
                        "{\"query\":\"sleep support\"}",
                        "{\"sufficient\":true,\"reason\":\"grounded\"}");
        SearchResult evidence = new SearchResult(
                1L,
                "sleep.md",
                "Sleep support guidance.",
                0.9,
                new EvidenceProvenance("version-1", "vector-1", 2));
        when(evidenceRetriever.retrieve(any())).thenReturn(RetrievalResult.ready("fake", List.of(evidence)));

        service.retrieve("I need sleep support", List.of());

        ArgumentCaptor<Object> traceEvidence = ArgumentCaptor.forClass(Object.class);
        verify(evaluationTraceService).put(eq("ragEvidence"), traceEvidence.capture());
        assertThat(traceEvidence.getValue().toString())
                .contains("E1", "version-1", "vector-1", "sourceIndex=2");
    }

    @Test
    void appliesParentDeduplicationAndOneBudgetAfterSingleRetrieval() {
        properties.getKnowledge().setEvidenceCharacterBudget(30);
        when(aiClient.completeJson(anyList(), anyMap()))
                .thenReturn(
                        "{\"query\":\"sleep\"}",
                        "{\"sufficient\":true,\"reason\":\"grounded\"}");
        SearchResult firstChild = new SearchResult(
                1L, "sleep.md", "同一个父章节的完整正文", 0.9,
                new EvidenceProvenance("v1", "child-1", 0)
                        .withParent("parent-1", 0, "睡眠 > 建议", 0, 10, 1, 1));
        SearchResult secondChild = new SearchResult(
                2L, "sleep.md", "同一个父章节的完整正文", 0.8,
                new EvidenceProvenance("v1", "child-2", 1)
                        .withParent("parent-1", 1, "睡眠 > 建议", 8, 18, 1, 1));
        when(evidenceRetriever.retrieve(any()))
                .thenReturn(RetrievalResult.ready("fake", List.of(firstChild, secondChild)));

        AgenticRagResult result = service.retrieve("睡不好", List.of());

        assertThat(result.evidence()).singleElement()
                .extracting(evidence -> evidence.provenance().vectorId())
                .isEqualTo("child-1");
        assertThat(result.evidence().stream().mapToInt(evidence -> evidence.content().length()).sum())
                .isLessThanOrEqualTo(30);
        verify(evidenceRetriever, times(1)).retrieve(any());
    }
}
