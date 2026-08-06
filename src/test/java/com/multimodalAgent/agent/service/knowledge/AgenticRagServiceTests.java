package com.multimodalAgent.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.service.ai.AiClient;
import com.multimodalAgent.agent.service.evaluation.EvaluationTraceService;
import com.multimodalAgent.agent.service.knowledge.retrieval.EvidenceRetriever;
import com.multimodalAgent.agent.service.knowledge.retrieval.RetrievalResult;
import com.multimodalAgent.agent.service.knowledge.retrieval.RetrievalStatus;
import java.util.List;
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

    private AgenticRagService service;

    @BeforeEach
    void setUp() {
        multimodalAgentProperties properties = new multimodalAgentProperties();
        properties.getKnowledge().setTopK(4);
        service = new AgenticRagService(
                evidenceRetriever,
                properties,
                aiClient,
                new ObjectMapper(),
                evaluationTraceService);
    }

    @Test
    void invalidReviewDoesNotBecomeSufficientJustBecauseEvidenceExists() {
        when(aiClient.completeJson(anyList(), anyMap()))
                .thenReturn(
                        "{\"reason\":\"support\",\"queries\":[\"sleep support\",\"sleep routine\"]}",
                        "{\"sufficient\":false,\"reason\":\"missing safety conditions\",\"followUpQueries\":[]}");
        when(evidenceRetriever.retrieve(any()))
                .thenReturn(RetrievalResult.ready(
                        "fake",
                        List.of(new SearchResult(null, "sleep.md", "sleep support", 0.9))));

        AgenticRagResult result = service.retrieve("I need sleep support", List.of());

        assertThat(result.sufficient()).isFalse();
        assertThat(result.retrievalStatus()).isEqualTo(RetrievalStatus.READY);
        assertThat(result.review()).contains("无法可靠完成证据复核");
        verify(evidenceRetriever, times(2)).retrieve(any());
    }

    @Test
    void retrievalFailureStopsReviewAndIsVisibleToTheCaller() {
        when(aiClient.completeJson(anyList(), anyMap()))
                .thenReturn("{\"reason\":\"support\",\"queries\":[\"sleep support\",\"sleep routine\"]}");
        when(evidenceRetriever.retrieve(any()))
                .thenReturn(RetrievalResult.failed("chroma", "Chroma unavailable"));

        AgenticRagResult result = service.retrieve("I need sleep support", List.of());

        assertThat(result.sufficient()).isFalse();
        assertThat(result.retrievalStatus()).isEqualTo(RetrievalStatus.FAILED);
        assertThat(result.contextBlock()).contains("知识库当前不可用");
        verify(aiClient, times(1)).completeJson(anyList(), anyMap());
    }
}
