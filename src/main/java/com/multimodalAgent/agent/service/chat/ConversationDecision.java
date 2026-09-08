package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.service.PsychologyAssessment;
import com.multimodalAgent.agent.service.routing.RoutingDecision;
import com.multimodalAgent.agent.service.memory.LongTermMemoryQuery;
import com.multimodalAgent.agent.service.memory.LongTermMemoryRecall;
import com.multimodalAgent.agent.service.memory.LongTermMemoryRetriever;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Final conversation policy module.
 *
 * <p>Combines analysis results with the risk floor and owns report lifecycle
 * side effects. Retrieval and model assessment stay behind
 * {@link ConversationAnalysis}.</p>
 */
@Service
public class ConversationDecision {

    private final ConversationAnalysis conversationAnalysis;
    private final ReportLifecycle reportLifecycle;
    private final LongTermMemoryRetriever memoryRetriever;

    @Autowired
    public ConversationDecision(
            ConversationAnalysis conversationAnalysis,
            ReportLifecycle reportLifecycle,
            LongTermMemoryRetriever memoryRetriever
    ) {
        this.conversationAnalysis = conversationAnalysis;
        this.reportLifecycle = reportLifecycle;
        this.memoryRetriever = memoryRetriever;
    }

    public ConversationDecision(ConversationAnalysis conversationAnalysis, ReportLifecycle reportLifecycle) {
        this(conversationAnalysis, reportLifecycle,
                query -> LongTermMemoryRecall.empty(LongTermMemoryRecall.Status.DISABLED, "test fallback"));
    }

    public ConversationDecisionResult decide(ConversationDecisionInput input) {
        ConversationAnalysisInput analysisInput = new ConversationAnalysisInput(
                input.modelInput(),
                input.previousHistory(),
                input.multimodalAnalysis());
        ConversationAnalysisResult analysis = conversationAnalysis.analyze(analysisInput);
        RoutingDecision routing = analysis.routing();
        Optional<PsychologyAssessment> assessmentResult = analysis.assessment();
        if (assessmentResult.isPresent()) {
            PsychologyAssessment assessment = assessmentResult.get();
            RiskLevel finalRisk = higherRisk(routing.riskLevel(), assessment.risk());
            if (finalRisk.ordinal() > routing.riskLevel().ordinal()) {
                routing = routing.withRiskFloor(finalRisk, "心理评估提高风险等级");
            }
            assessment = assessment.withRiskFloor(routing.riskLevel());
            if (routing.riskLevel().ordinal() >= RiskLevel.MEDIUM.ordinal()) {
                reportLifecycle.persist(new ConversationReportDraft(
                        input.identity(),
                        input.content(),
                        routing.needsRag(),
                        assessment,
                        input.multimodalAnalysis()));
            }
        }

        LongTermMemoryRecall memory = routing.riskLevel() == RiskLevel.HIGH
                ? LongTermMemoryRecall.empty(LongTermMemoryRecall.Status.DISABLED, "高风险响应不注入长期记忆")
                : memoryRetriever.recall(new LongTermMemoryQuery(
                        input.identity().userId(), input.identity().sessionId(), input.modelInput()));
        return new ConversationDecisionResult(routing, analysis.ragResult(), memory);
    }

    private RiskLevel higherRisk(RiskLevel left, RiskLevel right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }
}
