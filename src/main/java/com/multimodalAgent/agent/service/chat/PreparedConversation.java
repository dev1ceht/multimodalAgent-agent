package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.service.ai.AiMessage;
import com.multimodalAgent.agent.service.evaluation.EvaluationTraceService.Trace;
import java.util.List;

/**
 * 模型流式输出所需的完整对话快照。
 */
public record PreparedConversation(
        ConversationIdentity identity,
        List<AiMessage> messages,
        Trace trace
) {

    public PreparedConversation {
        messages = List.copyOf(messages);
    }
}
