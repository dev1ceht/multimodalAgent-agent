package com.multimodalAgent.agent.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.domain.MessageRole;
import com.multimodalAgent.agent.dto.ChatStreamEvent;
import com.multimodalAgent.agent.service.ai.AiClient;
import com.multimodalAgent.agent.service.ai.AiMessage;
import com.multimodalAgent.agent.service.evaluation.EvaluationTraceService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

class DefaultConversationResponseStreamerTests {

    private final ConversationMemory conversationMemory = mock(ConversationMemory.class);
    private final AiClient aiClient = mock(AiClient.class);
    private final EvaluationTraceService evaluationTraceService = mock(EvaluationTraceService.class);
    private final DefaultConversationResponseStreamer streamer = new DefaultConversationResponseStreamer(
            conversationMemory,
            aiClient,
            evaluationTraceService);

    @Test
    void successfulModelStreamSavesAssistantMessageAfterTokens() {
        PreparedConversation prepared = preparedConversation();
        when(aiClient.stream(prepared.messages())).thenReturn(Flux.just("你好", "，同学"));

        List<ServerSentEvent<ChatStreamEvent>> events = streamer.stream(prepared).collectList().block();

        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly("meta", "token", "token", "done");
        assertThat(events).extracting(event -> event.data().content())
                .containsExactly("", "你好", "，同学", "");
        verify(conversationMemory).append(
                prepared.identity(),
                MessageRole.ASSISTANT,
                "你好，同学");
    }

    @Test
    void emptyModelStreamEmitsErrorAndDoesNotSaveEmptyAssistantMessage() {
        PreparedConversation prepared = preparedConversation();
        when(aiClient.stream(prepared.messages())).thenReturn(Flux.empty());

        List<ServerSentEvent<ChatStreamEvent>> events = streamer.stream(prepared).collectList().block();

        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly("meta", "error", "done");
        assertThat(events.get(1).data().content()).contains("模型没有返回内容");
        verify(conversationMemory, never()).append(
                eq(prepared.identity()),
                eq(MessageRole.ASSISTANT),
                eq(""));
    }

    private PreparedConversation preparedConversation() {
        return new PreparedConversation(
                new ConversationIdentity(7L, 11L, "session-1", "student"),
                List.of(AiMessage.user("你好")),
                null);
    }
}
