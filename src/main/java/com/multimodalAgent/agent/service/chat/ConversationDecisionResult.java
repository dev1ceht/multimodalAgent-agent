package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.service.knowledge.AgenticRagResult;
import com.multimodalAgent.agent.service.routing.RoutingDecision;

/**
 * 一次对话决策的对外结果。
 */
public record ConversationDecisionResult(
        RoutingDecision routing,
        AgenticRagResult ragResult
) {
}
