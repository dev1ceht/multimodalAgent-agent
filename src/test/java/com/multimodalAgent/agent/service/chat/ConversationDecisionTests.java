package com.multimodalAgent.agent.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.domain.EmotionLabel;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.service.FunctionalImpact;
import com.multimodalAgent.agent.service.PsychologyAssessment;
import com.multimodalAgent.agent.service.knowledge.AgenticRagResult;
import com.multimodalAgent.agent.service.routing.RoutingDecision;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConversationDecisionTests {

    @Mock
    private ConversationAnalysis conversationAnalysis;

    @Mock
    private ReportLifecycle reportLifecycle;

    @Captor
    private ArgumentCaptor<ConversationReportDraft> reportDraftCaptor;

    @Test
    void knowledgeRagDoesNotBecomeRiskReport() {
        ConversationDecisionInput input = input();
        ConversationAnalysisResult analysis = new ConversationAnalysisResult(
                new RoutingDecision(true, RiskLevel.NONE, 0.9, "knowledge answer"),
                AgenticRagResult.empty(),
                Optional.empty());
        ConversationAnalysisInput analysisInput = analysisInput(input);
        when(conversationAnalysis.analyze(analysisInput)).thenReturn(analysis);

        ConversationDecisionResult result = decision().decide(input);

        assertThat(result.routing().needsRag()).isTrue();
        assertThat(result.routing().riskLevel()).isEqualTo(RiskLevel.NONE);
        assertThat(result.ragResult()).isSameAs(analysis.ragResult());
        verify(conversationAnalysis).analyze(analysisInput);
        verify(reportLifecycle, never()).persist(any());
    }

    @Test
    void highRiskDecisionPersistsReportDraftAfterRiskFloor() {
        ConversationDecisionInput input = input();
        PsychologyAssessment assessment = new PsychologyAssessment(
                EmotionLabel.HIGH_RISK,
                4.0,
                RiskLevel.HIGH,
                0.95,
                "needs attention",
                List.of(),
                FunctionalImpact.SIGNIFICANT,
                List.of("safety signal"));
        ConversationAnalysisResult analysis = new ConversationAnalysisResult(
                new RoutingDecision(true, RiskLevel.MEDIUM, 0.9, "medium route"),
                AgenticRagResult.empty(),
                Optional.of(assessment));
        ConversationAnalysisInput analysisInput = analysisInput(input);
        when(conversationAnalysis.analyze(analysisInput)).thenReturn(analysis);

        ConversationDecisionResult result = decision().decide(input);

        assertThat(result.routing().riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.routing().reason()).contains("心理");
        verify(reportLifecycle).persist(reportDraftCaptor.capture());
        assertThat(reportDraftCaptor.getValue().content()).isEqualTo(input.content());
        assertThat(reportDraftCaptor.getValue().needsRag()).isTrue();
        assertThat(reportDraftCaptor.getValue().assessment().risk()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void lowRiskAssessmentRaisesRouteButDoesNotCreateReport() {
        ConversationDecisionInput input = input();
        PsychologyAssessment assessment = new PsychologyAssessment(
                EmotionLabel.ANXIETY,
                1.0,
                RiskLevel.LOW,
                0.7,
                "mild concern");
        ConversationAnalysisResult analysis = new ConversationAnalysisResult(
                RoutingDecision.ordinary("ordinary route"),
                AgenticRagResult.empty(),
                Optional.of(assessment));
        ConversationAnalysisInput analysisInput = analysisInput(input);
        when(conversationAnalysis.analyze(analysisInput)).thenReturn(analysis);

        ConversationDecisionResult result = decision().decide(input);

        assertThat(result.routing().riskLevel()).isEqualTo(RiskLevel.LOW);
        verify(reportLifecycle, never()).persist(any());
    }

    private ConversationDecision decision() {
        return new ConversationDecision(conversationAnalysis, reportLifecycle);
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
