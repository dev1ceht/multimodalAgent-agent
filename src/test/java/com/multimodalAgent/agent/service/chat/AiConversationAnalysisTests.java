package com.multimodalAgent.agent.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.domain.EmotionLabel;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.service.FunctionalImpact;
import com.multimodalAgent.agent.service.PsychologicalAssessmentService;
import com.multimodalAgent.agent.service.PsychologyAssessment;
import com.multimodalAgent.agent.service.ai.AiMessage;
import com.multimodalAgent.agent.service.knowledge.AgenticRagResult;
import com.multimodalAgent.agent.service.knowledge.AgenticRagService;
import com.multimodalAgent.agent.service.multimodal.MultimodalAnalysis;
import com.multimodalAgent.agent.service.routing.RequestRouter;
import com.multimodalAgent.agent.service.routing.RoutingDecision;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiConversationAnalysisTests {

    @Mock
    private RequestRouter requestRouter;

    @Mock
    private PsychologicalAssessmentService assessmentService;

    @Mock
    private AgenticRagService agenticRagService;

    @Mock
    private ConversationHistoryMapper historyMapper;

    @Test
    void mapsHistoryAndRetrievesKnowledgeWithoutAssessmentForOrdinaryRequest() {
        ConversationDecisionInput input = input();
        List<AiMessage> modelHistory = List.of(AiMessage.assistant("Previous answer"));
        RoutingDecision routing = new RoutingDecision(true, RiskLevel.NONE, 0.9, "knowledge answer");
        when(historyMapper.toAiMessages(input.previousHistory())).thenReturn(modelHistory);
        when(requestRouter.decide(input.modelInput(), modelHistory, RiskLevel.NONE)).thenReturn(routing);
        when(agenticRagService.retrieve(input.modelInput(), modelHistory))
                .thenReturn(AgenticRagResult.empty());

        ConversationAnalysisResult result = analysis().analyze(analysisInput(input));

        assertThat(result.routing()).isSameAs(routing);
        assertThat(result.assessment()).isEmpty();
        verify(historyMapper).toAiMessages(input.previousHistory());
        verify(agenticRagService).retrieve(input.modelInput(), modelHistory);
        verify(assessmentService, never()).assess(any(), any());
    }

    @Test
    void assessesMediumRiskAfterRouting() {
        ConversationDecisionInput input = input();
        List<AiMessage> modelHistory = List.of(AiMessage.user("Previous concern"));
        RoutingDecision routing = new RoutingDecision(true, RiskLevel.MEDIUM, 0.8, "risk signal");
        PsychologyAssessment assessment = new PsychologyAssessment(
                EmotionLabel.ANXIETY,
                3.0,
                RiskLevel.MEDIUM,
                0.9,
                "needs support",
                List.of(),
                FunctionalImpact.MILD,
                List.of("risk signal"));
        when(historyMapper.toAiMessages(input.previousHistory())).thenReturn(modelHistory);
        when(requestRouter.decide(input.modelInput(), modelHistory, RiskLevel.NONE)).thenReturn(routing);
        when(agenticRagService.retrieve(input.modelInput(), modelHistory))
                .thenReturn(AgenticRagResult.empty());
        when(assessmentService.assess(input.modelInput(), modelHistory)).thenReturn(assessment);

        ConversationAnalysisResult result = analysis().analyze(analysisInput(input));

        assertThat(result.routing()).isSameAs(routing);
        assertThat(result.assessment()).containsSame(assessment);
        verify(assessmentService).assess(input.modelInput(), modelHistory);
    }

    @Test
    void reusesFusedMultimodalAssessmentAndPassesExternalRiskToRouter() {
        PsychologyAssessment fusedAssessment = new PsychologyAssessment(
                EmotionLabel.ANXIETY,
                3.0,
                RiskLevel.MEDIUM,
                0.92,
                "multimodal support needed");
        ConversationDecisionInput input = new ConversationDecisionInput(
                new ConversationIdentity(7L, 11L, "session-1", "student"),
                "I cannot sleep",
                "I cannot sleep",
                new ConversationHistory(List.of()),
                new MultimodalAnalysis(
                        "I cannot sleep",
                        "I cannot sleep",
                        fusedAssessment,
                        List.of(),
                        "audio and text",
                        "[]"));
        List<AiMessage> modelHistory = List.of(AiMessage.user("Previous concern"));
        RoutingDecision routing = new RoutingDecision(true, RiskLevel.MEDIUM, 0.85, "multimodal risk");
        when(historyMapper.toAiMessages(input.previousHistory())).thenReturn(modelHistory);
        when(requestRouter.decide(input.modelInput(), modelHistory, RiskLevel.MEDIUM)).thenReturn(routing);
        when(agenticRagService.retrieve(input.modelInput(), modelHistory))
                .thenReturn(AgenticRagResult.empty());

        ConversationAnalysisResult result = analysis().analyze(analysisInput(input));

        assertThat(result.assessment()).containsSame(fusedAssessment);
        verify(requestRouter).decide(input.modelInput(), modelHistory, RiskLevel.MEDIUM);
        verify(assessmentService, never()).assess(any(), any());
    }

    private AiConversationAnalysis analysis() {
        return new AiConversationAnalysis(
                requestRouter,
                assessmentService,
                agenticRagService,
                historyMapper);
    }

    private ConversationAnalysisInput analysisInput(ConversationDecisionInput input) {
        return new ConversationAnalysisInput(
                input.modelInput(),
                input.previousHistory(),
                input.multimodalAnalysis());
    }

    private ConversationDecisionInput input() {
        return new ConversationDecisionInput(
                new ConversationIdentity(7L, 11L, "session-1", "student"),
                "Explain sleep support",
                "Explain sleep support",
                new ConversationHistory(List.of()),
                null);
    }
}
