package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.service.PsychologicalAssessmentService;
import com.multimodalAgent.agent.service.PsychologyAssessment;
import com.multimodalAgent.agent.service.ai.AiMessage;
import com.multimodalAgent.agent.service.knowledge.AgenticRagResult;
import com.multimodalAgent.agent.service.knowledge.AgenticRagService;
import com.multimodalAgent.agent.service.multimodal.MultimodalAnalysis;
import com.multimodalAgent.agent.service.routing.RequestRouter;
import com.multimodalAgent.agent.service.routing.RoutingDecision;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 基于模型路由、RAG 和心理评估模块的分析适配器。
 */
@Service
public class AiConversationAnalysis implements ConversationAnalysis {

    private final RequestRouter requestRouter;
    private final PsychologicalAssessmentService assessmentService;
    private final AgenticRagService agenticRagService;
    private final ConversationHistoryMapper historyMapper;

    public AiConversationAnalysis(
            RequestRouter requestRouter,
            PsychologicalAssessmentService assessmentService,
            AgenticRagService agenticRagService,
            ConversationHistoryMapper historyMapper
    ) {
        this.requestRouter = requestRouter;
        this.assessmentService = assessmentService;
        this.agenticRagService = agenticRagService;
        this.historyMapper = historyMapper;
    }

    @Override
    public ConversationAnalysisResult analyze(ConversationAnalysisInput input) {
        List<AiMessage> modelHistory = historyMapper.toAiMessages(input.previousHistory());
        RiskLevel externalRisk = externalRisk(input.multimodalAnalysis());
        RoutingDecision routing = requestRouter.decide(
                input.modelInput(),
                modelHistory,
                externalRisk);
        AgenticRagResult ragResult = AgenticRagResult.empty();

        if (routing.needsRag()) {
            ragResult = agenticRagService.retrieve(input.modelInput(), modelHistory);
        }

        PsychologyAssessment assessment = null;
        if (routing.riskLevel().ordinal() >= RiskLevel.MEDIUM.ordinal()) {
            assessment = fusedAssessment(input.multimodalAnalysis())
                    .orElseGet(() -> assessmentService.assess(input.modelInput(), modelHistory));
        }

        return new ConversationAnalysisResult(routing, ragResult, Optional.ofNullable(assessment));
    }

    private RiskLevel externalRisk(MultimodalAnalysis multimodalAnalysis) {
        return fusedAssessment(multimodalAnalysis)
                .map(PsychologyAssessment::risk)
                .orElse(RiskLevel.NONE);
    }

    private Optional<PsychologyAssessment> fusedAssessment(
            MultimodalAnalysis multimodalAnalysis
    ) {
        return multimodalAnalysis == null
                ? Optional.empty()
                : Optional.ofNullable(multimodalAnalysis.fusedAssessment());
    }
}
