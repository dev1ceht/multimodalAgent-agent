package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.service.ai.AiMessage;
import com.multimodalAgent.agent.service.PsychologicalAssessmentService;
import com.multimodalAgent.agent.service.PsychologyAssessment;
import com.multimodalAgent.agent.service.knowledge.AgenticRagResult;
import com.multimodalAgent.agent.service.knowledge.AgenticRagService;
import com.multimodalAgent.agent.service.routing.RequestRouter;
import com.multimodalAgent.agent.service.routing.RoutingDecision;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 对话决策模块。
 *
 * <p>集中处理 RAG 需求、风险等级、心理评估和报告任务创建；
 * {@code needsRag} 与 {@code riskLevel} 始终保持正交。</p>
 */
@Service
public class ConversationDecision {

    private final RequestRouter requestRouter;
    private final PsychologicalAssessmentService assessmentService;
    private final AgenticRagService agenticRagService;
    private final ReportLifecycle reportLifecycle;
    private final ConversationHistoryMapper historyMapper;

    public ConversationDecision(
            RequestRouter requestRouter,
            PsychologicalAssessmentService assessmentService,
            AgenticRagService agenticRagService,
            ReportLifecycle reportLifecycle,
            ConversationHistoryMapper historyMapper
    ) {
        this.requestRouter = requestRouter;
        this.assessmentService = assessmentService;
        this.agenticRagService = agenticRagService;
        this.reportLifecycle = reportLifecycle;
        this.historyMapper = historyMapper;
    }

    public ConversationDecisionResult decide(ConversationDecisionInput input) {
        List<AiMessage> modelHistory = historyMapper.toAiMessages(input.previousHistory());
        RiskLevel externalRisk = input.multimodalAnalysis() == null
                ? RiskLevel.NONE
                : input.multimodalAnalysis().fusedAssessment().risk();
        RoutingDecision routing = requestRouter.decide(
                input.modelInput(),
                modelHistory,
                externalRisk);
        AgenticRagResult ragResult = AgenticRagResult.empty();

        if (routing.needsRag()) {
            ragResult = agenticRagService.retrieve(input.modelInput(), modelHistory);
        }

        if (routing.riskLevel().ordinal() >= RiskLevel.MEDIUM.ordinal()) {
            PsychologyAssessment assessment = input.multimodalAnalysis() == null
                    ? assessmentService.assess(input.modelInput(), modelHistory)
                    : input.multimodalAnalysis().fusedAssessment();
            RiskLevel finalRisk = higherRisk(routing.riskLevel(), assessment.risk());
            if (finalRisk.ordinal() > routing.riskLevel().ordinal()) {
                routing = routing.withRiskFloor(finalRisk, "心理评估提高风险等级");
            }
            assessment = assessment.withRiskFloor(routing.riskLevel());
            reportLifecycle.persist(new ConversationReportDraft(
                    input.identity(),
                    input.content(),
                    routing.needsRag(),
                    assessment,
                    input.multimodalAnalysis()));
        }

        return new ConversationDecisionResult(routing, ragResult);
    }

    private RiskLevel higherRisk(RiskLevel left, RiskLevel right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }
}
