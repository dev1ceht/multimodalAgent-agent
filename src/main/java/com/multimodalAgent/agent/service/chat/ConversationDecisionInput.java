package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.service.ai.AiMessage;
import com.multimodalAgent.agent.service.multimodal.MultimodalAnalysis;
import java.util.List;

/**
 * 路由与心理安全决策所需的输入快照。
 */
public record ConversationDecisionInput(
        ConversationIdentity identity,
        String content,
        String modelInput,
        List<AiMessage> previousHistory,
        MultimodalAnalysis multimodalAnalysis
) {

    public ConversationDecisionInput {
        previousHistory = List.copyOf(previousHistory);
    }
}
