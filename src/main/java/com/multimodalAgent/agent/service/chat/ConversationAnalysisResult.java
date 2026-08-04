package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.service.PsychologyAssessment;
import com.multimodalAgent.agent.service.knowledge.AgenticRagResult;
import com.multimodalAgent.agent.service.routing.RoutingDecision;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次对话分析的结果。
 *
 * <p>风险提升和报告副作用由上层决策模块处理；这里保留分析阶段的原始评估结果。</p>
 */
public record ConversationAnalysisResult(
        RoutingDecision routing,
        AgenticRagResult ragResult,
        Optional<PsychologyAssessment> assessment
) {

    public ConversationAnalysisResult {
        routing = Objects.requireNonNull(routing, "routing");
        ragResult = Objects.requireNonNull(ragResult, "ragResult");
        assessment = assessment == null ? Optional.empty() : assessment;
    }
}
