package com.multimodalAgent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.config.MindCareAgentProperties;
import com.multimodalAgent.agent.dto.ChatRequest;
import com.multimodalAgent.agent.dto.ChatStreamEvent;
import com.multimodalAgent.agent.service.chat.AgentConversationGateway;
import com.multimodalAgent.agent.service.chat.ConversationPreparation;
import com.multimodalAgent.agent.service.chat.ConversationRequest;
import com.multimodalAgent.agent.service.chat.ConversationResponseStreamer;
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

    @Mock
    private AgentConversationGateway agentConversationService;

    @Captor
    private ArgumentCaptor<ConversationRequest> requestCaptor;

    @Test
    void defaultChatServiceDelegatesToSaaAgent() {
        ChatRequest request = new ChatRequest("session-1", "  你好  ");
        when(agentConversationService.stream(any(ConversationRequest.class), eq(true)))
                .thenReturn(Flux.just(event("done", ChatStreamEvent.done("session-1"))));
        MindCareAgentProperties agentProperties = new MindCareAgentProperties();

        StepVerifier.create(new ChatService(
                                conversationPreparation,
                                conversationResponseStreamer,
                                agentProperties,
                                agentConversationService)
                        .streamChat(7L, request))
                .assertNext(event -> assertThat(event.event()).isEqualTo("done"))
                .verifyComplete();

        assertThat(agentProperties.isSaaMode()).isTrue();
        verify(agentConversationService).stream(requestCaptor.capture(), eq(true));
        assertThat(requestCaptor.getValue().userId()).isEqualTo(7L);
        assertThat(requestCaptor.getValue().request()).isSameAs(request);
        assertThat(requestCaptor.getValue().multimodalAnalysis()).isNull();
        verifyNoInteractions(conversationPreparation, conversationResponseStreamer);
    }

    @Test
    void saaRuntimeFailureBecomesSingleErrorEvent() {
        when(agentConversationService.stream(any(ConversationRequest.class), eq(true)))
                .thenReturn(Flux.error(new IllegalStateException("agent failed")));

        StepVerifier.create(new ChatService(
                                conversationPreparation,
                                conversationResponseStreamer,
                                new MindCareAgentProperties(),
                                agentConversationService)
                        .streamChat(7L, new ChatRequest(null, "你好")))
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("error");
                    assertThat(event.data()).isNotNull();
                    assertThat(event.data().content()).isEqualTo("服务暂时不可用，请稍后重试。");
                })
                .verifyComplete();
        verifyNoInteractions(conversationPreparation, conversationResponseStreamer);
    }

    private ServerSentEvent<ChatStreamEvent> event(String name, ChatStreamEvent data) {
        return ServerSentEvent.builder(data).event(name).build();
    }
}
