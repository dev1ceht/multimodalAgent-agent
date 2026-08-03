package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.service.multimodal.MultimodalAnalysis;

/**
 * 路由与心理安全决策所需的输入快照。
 */
public record ConversationDecisionInput(
        ConversationIdentity identity,
        String content,
        String modelInput,
        ConversationHistory previousHistory,
        MultimodalAnalysis multimodalAnalysis
) {
}
