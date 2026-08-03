package com.multimodalAgent.agent.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.domain.EmotionLabel;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.service.FunctionalImpact;
import com.multimodalAgent.agent.service.PsychologicalAssessmentService;
import com.multimodalAgent.agent.service.PsychologyAssessment;
import com.multimodalAgent.agent.service.knowledge.AgenticRagResult;
import com.multimodalAgent.agent.service.knowledge.AgenticRagService;
import com.multimodalAgent.agent.service.routing.RequestRouter;
import com.multimodalAgent.agent.service.routing.RoutingDecision;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConversationDecisionTests {

    @Mock
    private RequestRouter requestRouter;

    @Mock
    private PsychologicalAssessmentService assessmentService;

    @Mock
    private AgenticRagService agenticRagService;

    @Mock
    private ReportLifecycle reportLifecycle;

    @Mock
    private ConversationHistoryMapper historyMapper;

    @Captor
    private ArgumentCaptor<ConversationReportDraft> reportDraftCaptor;

    @BeforeEach
    void setUp() {
        when(historyMapper.toAiMessages(any())).thenReturn(List.of());
    }

    @Test
    void knowledgeRagDoesNotBecomeRiskReport() {
        ConversationDecisionInput input = input();
        RoutingDecision routing = new RoutingDecision(true, RiskLevel.NONE, 0.9, "知识问答");
        when(requestRouter.decide(anyString(), anyList(), org.mockito.ArgumentMatchers.eq(RiskLevel.NONE)))
                .thenReturn(routing);
        when(agenticRagService.retrieve(anyString(), anyList()))
                .thenReturn(AgenticRagResult.empty());

        ConversationDecisionResult result = decision().decide(input);

        assertThat(result.routing().needsRag()).isTrue();
        assertThat(result.routing().riskLevel()).isEqualTo(RiskLevel.NONE);
        verify(agenticRagService).retrieve("请解释睡眠支持", List.of());
        verify(assessmentService, never()).assess(anyString(), anyList());
        verify(reportLifecycle, never()).persist(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void highRiskDecisionPersistsReportDraftAfterRiskFloor() {
        ConversationDecisionInput input = input();
        when(requestRouter.decide(anyString(), anyList(), org.mockito.ArgumentMatchers.eq(RiskLevel.NONE)))
                .thenReturn(RoutingDecision.highRisk("命中安全信号"));
        when(agenticRagService.retrieve(anyString(), anyList()))
                .thenReturn(AgenticRagResult.empty());
        when(assessmentService.assess(anyString(), anyList()))
                .thenReturn(new PsychologyAssessment(
                        EmotionLabel.HIGH_RISK,
                        4.0,
                        RiskLevel.HIGH,
                        0.95,
                        "需要关注",
                        List.of(),
                        FunctionalImpact.SIGNIFICANT,
                        List.of("安全信号")));

        ConversationDecisionResult result = decision().decide(input);

        assertThat(result.routing().riskLevel()).isEqualTo(RiskLevel.HIGH);
        verify(reportLifecycle).persist(reportDraftCaptor.capture());
        assertThat(reportDraftCaptor.getValue().content()).isEqualTo("请解释睡眠支持");
        assertThat(reportDraftCaptor.getValue().needsRag()).isTrue();
        assertThat(reportDraftCaptor.getValue().assessment().risk()).isEqualTo(RiskLevel.HIGH);
    }

    private ConversationDecision decision() {
        return new ConversationDecision(
                requestRouter,
                assessmentService,
                agenticRagService,
                reportLifecycle,
                historyMapper);
    }

    private ConversationDecisionInput input() {
        return new ConversationDecisionInput(
                new ConversationIdentity(7L, 11L, "session-1", "student"),
                "请解释睡眠支持",
                "请解释睡眠支持",
                new ConversationHistory(List.of()),
                null);
    }
}
