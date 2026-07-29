package com.multimodalAgent.agent.service.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.service.ai.AiClient;
import com.multimodalAgent.agent.service.evaluation.EvaluationTraceService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RequestRouterTests {

    private AiClient aiClient;
    private RequestRouter router;

    @BeforeEach
    void setUp() {
        aiClient = mock(AiClient.class);
        router = new RequestRouter(
                aiClient,
                new ObjectMapper(),
                mock(EvaluationTraceService.class));
    }

    @Test
    void explicitHighRiskBypassesModelAndForcesRag() {
        RoutingDecision decision = router.decide("我今晚不想活了，现在一个人在宿舍。", List.of());

        assertTrue(decision.needsRag());
        assertEquals(RiskLevel.HIGH, decision.riskLevel());
        verify(aiClient, never()).complete(anyList());
    }

    @Test
    void knowledgeQuestionCanUseRagWithoutAssigningUserRisk() {
        when(aiClient.complete(anyList())).thenReturn(
                """
                {"needsRag":true,"riskLevel":"NONE","confidence":0.93,
                 "reason":"危机支持知识问答"}
                """);

        RoutingDecision decision = router.decide("120 和 110 在危机中分别做什么？", List.of());

        assertTrue(decision.needsRag());
        assertEquals(RiskLevel.NONE, decision.riskLevel());
        assertEquals(0.93, decision.confidence(), 0.001);
    }

    @Test
    void highRiskTopicAloneDoesNotBypassModelAsARealWorldCrisis() {
        when(aiClient.complete(anyList())).thenReturn(
                """
                {"needsRag":true,"riskLevel":"NONE","confidence":0.91,
                 "reason":"自杀预防知识问答，没有现实危险陈述"}
                """);

        RoutingDecision decision = router.decide("担心有人自杀时，为什么可以直接询问？", List.of());

        assertTrue(decision.needsRag());
        assertEquals(RiskLevel.NONE, decision.riskLevel());
        verify(aiClient).complete(anyList());
    }

    @Test
    void nonNoneRiskCannotDisableRag() {
        when(aiClient.complete(anyList())).thenReturn(
                """
                {"needsRag":false,"riskLevel":"LOW","confidence":0.8,
                 "reason":"轻度压力"}
                """);

        RoutingDecision decision = router.decide("最近有一点考试压力。", List.of());

        assertTrue(decision.needsRag());
        assertEquals(RiskLevel.LOW, decision.riskLevel());
    }

    @Test
    void invalidModelOutputFallsBackToOrdinaryRoute() {
        when(aiClient.complete(anyList())).thenReturn("CHAT");

        RoutingDecision decision = router.decide("帮我解释 Java 集合。", List.of());

        assertFalse(decision.needsRag());
        assertEquals(RiskLevel.NONE, decision.riskLevel());
    }
}
