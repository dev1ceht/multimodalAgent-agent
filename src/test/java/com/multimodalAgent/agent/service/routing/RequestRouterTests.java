package com.multimodalAgent.agent.service.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
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
        verify(aiClient, never()).completeJson(anyList(), anyMap());
    }

    @Test
    void knowledgeQuestionCanUseRagWithoutAssigningUserRisk() {
        when(aiClient.completeJson(anyList(), anyMap())).thenReturn(
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
        when(aiClient.completeJson(anyList(), anyMap())).thenReturn(
                """
                {"needsRag":true,"riskLevel":"NONE","confidence":0.91,
                 "reason":"自杀预防知识问答，没有现实危险陈述"}
                """);

        RoutingDecision decision = router.decide("担心有人自杀时，为什么可以直接询问？", List.of());

        assertTrue(decision.needsRag());
        assertEquals(RiskLevel.NONE, decision.riskLevel());
        verify(aiClient).completeJson(anyList(), anyMap());
    }

    @Test
    void nonNoneRiskCannotDisableRag() {
        when(aiClient.completeJson(anyList(), anyMap())).thenReturn(
                """
                {"needsRag":false,"riskLevel":"LOW","confidence":0.8,
                 "reason":"轻度压力"}
                """);

        RoutingDecision decision = router.decide("最近有一点考试压力。", List.of());

        assertTrue(decision.needsRag());
        assertEquals(RiskLevel.LOW, decision.riskLevel());
    }

    @Test
    void mentalHealthKnowledgeTopicCannotDisableRag() {
        when(aiClient.completeJson(anyList(), anyMap())).thenReturn(
                """
                {"needsRag":false,"riskLevel":"NONE","confidence":0.95,
                 "reason":"普通健康知识问答"}
                """);

        RoutingDecision decision = router.decide("只要周末补觉，平时天天熬夜也不会影响健康，对吗？", List.of());

        assertTrue(decision.needsRag());
        assertEquals(RiskLevel.NONE, decision.riskLevel());
    }

    @Test
    void ordinaryNonMentalHealthQuestionRemainsOutOfRag() {
        when(aiClient.completeJson(anyList(), anyMap())).thenReturn(
                """
                {"needsRag":false,"riskLevel":"NONE","confidence":0.98,
                 "reason":"普通编程问题"}
                """);

        RoutingDecision decision = router.decide("帮我解释 Java 集合。", List.of());

        assertFalse(decision.needsRag());
        assertEquals(RiskLevel.NONE, decision.riskLevel());
    }

    @Test
    void genericCampusRequestsDoNotTriggerMentalHealthTopicFloor() {
        when(aiClient.completeJson(anyList(), anyMap())).thenReturn(
                """
                {"needsRag":false,"riskLevel":"NONE","confidence":0.98,
                 "reason":"普通校园事务"}
                """);

        assertFalse(router.decide("帮我列一个普通课程论文提纲。", List.of()).needsRag());
        assertFalse(router.decide("室友借了我的充电器，我怎么礼貌提醒他归还？", List.of()).needsRag());
    }

    @Test
    void explicitMentalHealthKnowledgePhrasesForceRag() {
        when(aiClient.completeJson(anyList(), anyMap())).thenReturn(
                """
                {"needsRag":false,"riskLevel":"NONE","confidence":0.98,
                 "reason":"普通知识问题"}
                """);

        assertTrue(router.decide("你就直接告诉我这是惊恐障碍。", List.of()).needsRag());
        assertTrue(router.decide("恋爱关系中查看手机和限制社交正常吗？", List.of()).needsRag());
        assertTrue(router.decide("我想转专业但不知道怎样求助。", List.of()).needsRag());
    }

    @Test
    void invalidModelOutputFallsBackToOrdinaryRoute() {
        when(aiClient.completeJson(anyList(), anyMap())).thenReturn("CHAT");

        RoutingDecision decision = router.decide("帮我解释 Java 集合。", List.of());

        assertFalse(decision.needsRag());
        assertEquals(RiskLevel.NONE, decision.riskLevel());
    }
}
