package com.multimodalAgent.agent.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.MessageRole;
import com.multimodalAgent.agent.service.PrivacySanitizer;
import com.multimodalAgent.agent.service.ai.AiMessage;
import com.multimodalAgent.agent.service.evaluation.EvaluationTraceService;
import com.multimodalAgent.agent.service.knowledge.AgenticRagResult;
import com.multimodalAgent.agent.service.routing.RoutingDecision;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultConversationPreparationTests {

    @Mock
    private ConversationMemory conversationMemory;

    @Mock
    private ConversationDecision conversationDecision;

    @Mock
    private ConversationPromptBuilder promptBuilder;

    @Mock
    private PrivacySanitizer privacySanitizer;

    @Mock
    private EvaluationTraceService evaluationTraceService;

    @Test
    void preparationPersistsUserMessageBeforeBuildingModelInput() {
        multimodalAgentProperties properties = new multimodalAgentProperties();
        properties.getAi().setProvider("mock");
        DefaultConversationPreparation preparation = new DefaultConversationPreparation(
                properties,
                conversationMemory,
                conversationDecision,
                promptBuilder,
                privacySanitizer,
                evaluationTraceService);
        ConversationRequest request = new ConversationRequest(
                7L,
                new com.multimodalAgent.agent.dto.ChatRequest(null, "你好"),
                null);
        ConversationIdentity identity = new ConversationIdentity(7L, 11L, "session-1", "student");
        ConversationHistory previousHistory = new ConversationHistory(List.of(
                new ConversationMessage(MessageRole.ASSISTANT, "之前回答")));
        ConversationHistory modelHistory = new ConversationHistory(List.of(
                new ConversationMessage(MessageRole.ASSISTANT, "之前回答"),
                new ConversationMessage(MessageRole.USER, "你好")));
        RoutingDecision routing = RoutingDecision.ordinary("普通聊天");
        ConversationDecisionResult decision = new ConversationDecisionResult(
                routing,
                AgenticRagResult.empty());
        List<AiMessage> messages = List.of(AiMessage.system("system"), AiMessage.user("你好"));

        when(privacySanitizer.sanitize("你好")).thenReturn("你好");
        when(conversationMemory.open(7L, null, "你好")).thenReturn(identity);
        when(conversationMemory.recentHistory(identity)).thenReturn(previousHistory);
        when(conversationMemory.appendCurrentInputWithinWindow(previousHistory, "你好"))
                .thenReturn(modelHistory);
        when(conversationDecision.decide(any())).thenReturn(decision);
        when(promptBuilder.build(identity, routing, AgenticRagResult.empty(), modelHistory))
                .thenReturn(messages);

        PreparedConversation prepared = preparation.prepare(request);

        assertThat(prepared.identity()).isEqualTo(identity);
        assertThat(prepared.messages()).isEqualTo(messages);
        verify(conversationMemory).append(identity, MessageRole.USER, "你好");
        verify(conversationDecision).decide(any(ConversationDecisionInput.class));
        verify(promptBuilder).build(identity, routing, AgenticRagResult.empty(), modelHistory);
    }
}
