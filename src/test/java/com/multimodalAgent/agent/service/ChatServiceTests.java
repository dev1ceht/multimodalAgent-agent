package com.multimodalAgent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.dto.ChatRequest;
import com.multimodalAgent.agent.dto.ChatStreamEvent;
import com.multimodalAgent.agent.service.ai.AiMessage;
import com.multimodalAgent.agent.service.chat.ConversationIdentity;
import com.multimodalAgent.agent.service.chat.ConversationPreparation;
import com.multimodalAgent.agent.service.chat.ConversationRequest;
import com.multimodalAgent.agent.service.chat.ConversationResponseStreamer;
import com.multimodalAgent.agent.service.chat.PreparedConversation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class ChatServiceTests {

    @Mock
    private ConversationPreparation conversationPreparation;

    @Mock
    private ConversationResponseStreamer conversationResponseStreamer;

    @Captor
    private ArgumentCaptor<ConversationRequest> requestCaptor;

    @Test
    void streamChatDelegatesPreparationAndResponseStreaming() {
        ChatRequest request = new ChatRequest("session-1", "  你好  ");
        PreparedConversation prepared = preparedConversation();
        when(conversationPreparation.prepare(any())).thenReturn(prepared);
        when(conversationResponseStreamer.stream(prepared))
                .thenReturn(Flux.just(event("done", ChatStreamEvent.done("session-1"))));

        StepVerifier.create(new ChatService(conversationPreparation, conversationResponseStreamer)
                        .streamChat(7L, request))
                .assertNext(event -> assertThat(event.event()).isEqualTo("done"))
                .verifyComplete();

        verify(conversationPreparation).prepare(requestCaptor.capture());
        assertThat(requestCaptor.getValue().userId()).isEqualTo(7L);
        assertThat(requestCaptor.getValue().request()).isSameAs(request);
        assertThat(requestCaptor.getValue().multimodalAnalysis()).isNull();
        verify(conversationResponseStreamer).stream(prepared);
    }

    @Test
    void preparationFailureBecomesSingleErrorEvent() {
        when(conversationPreparation.prepare(any()))
                .thenThrow(new IllegalStateException("preparation failed"));

        StepVerifier.create(new ChatService(conversationPreparation, conversationResponseStreamer)
                        .streamChat(7L, new ChatRequest(null, "你好")))
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("error");
                    assertThat(event.data()).isNotNull();
                    assertThat(event.data().content()).contains("preparation failed");
                })
                .verifyComplete();
    }

    private PreparedConversation preparedConversation() {
        return new PreparedConversation(
                new ConversationIdentity(7L, 11L, "session-1", "student"),
                List.of(AiMessage.user("你好")),
                null);
    }

    private ServerSentEvent<ChatStreamEvent> event(String name, ChatStreamEvent data) {
        return ServerSentEvent.builder(data).event(name).build();
    }
}
