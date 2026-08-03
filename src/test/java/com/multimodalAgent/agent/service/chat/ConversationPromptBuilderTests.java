package com.multimodalAgent.agent.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.service.ai.AiMessage;
import com.multimodalAgent.agent.service.knowledge.AgenticRagResult;
import com.multimodalAgent.agent.service.routing.RoutingDecision;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationPromptBuilderTests {

    private final ConversationPromptBuilder promptBuilder = new ConversationPromptBuilder();

    @Test
    void builderKeepsTheWindowedHistoryAndCurrentUserMessage() {
        List<AiMessage> history = List.of(
                AiMessage.assistant("上一轮回答"),
                AiMessage.user("当前问题"));

        List<AiMessage> messages = promptBuilder.build(
                new ConversationIdentity(7L, 11L, "session-1", "student"),
                new RoutingDecision(true, RiskLevel.NONE, 1.0, "知识问答"),
                AgenticRagResult.empty(),
                history);

        assertThat(messages).containsSubsequence(
                AiMessage.assistant("上一轮回答"),
                AiMessage.user("当前问题"));
    }
}
