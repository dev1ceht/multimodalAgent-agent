package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.service.ai.AiMessage;
import com.multimodalAgent.agent.service.ai.PromptTemplates;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 将领域会话历史转换为模型调用所需消息的适配器。
 */
@Service
public class ConversationHistoryMapper {

    public List<AiMessage> toAiMessages(ConversationHistory history) {
        return history.messages().stream()
                .map(this::toAiMessage)
                .toList();
    }

    private AiMessage toAiMessage(ConversationMessage message) {
        return switch (message.role()) {
            case ASSISTANT -> AiMessage.assistant(message.content());
            case SYSTEM -> PromptTemplates.multimodalContext(message.content());
            case USER -> AiMessage.user(message.content());
        };
    }
}
