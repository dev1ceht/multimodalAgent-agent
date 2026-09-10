package com.multimodalAgent.agent.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.config.MindCareAgentProperties;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.MessageRole;
import com.multimodalAgent.agent.dto.ChatRequest;
import com.multimodalAgent.agent.dto.ChatStreamEvent;
import com.multimodalAgent.agent.service.PrivacySanitizer;
import com.multimodalAgent.agent.service.PsychologicalAssessmentService;
import com.multimodalAgent.agent.service.agentruntime.AgentEvent;
import com.multimodalAgent.agent.service.agentruntime.MindCareAgentRuntime;
import com.multimodalAgent.agent.service.evaluation.EvaluationTraceService;
import com.multimodalAgent.agent.service.routing.RequestRouter;
import com.multimodalAgent.agent.service.routing.RoutingDecision;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

class AgentStreamingTests {

    @Test
    void persistsAssistantOnlyAfterDoneAndMapsPublicSseEvents() {
        ConversationMemory memory = mock(ConversationMemory.class);
        ConversationIdentity identity = new ConversationIdentity(7L, 11L, "session-1", "student");
        when(memory.open(7L, null, "hello")).thenReturn(identity);
        when(memory.recentHistory(identity)).thenReturn(new ConversationHistory(List.of()));
        RequestRouter router = mock(RequestRouter.class);
        when(router.decide(any(), any(), any())).thenReturn(RoutingDecision.ordinary("test"));
        MindCareAgentRuntime runtime = mock(MindCareAgentRuntime.class);
        when(runtime.run(any())).thenReturn(Flux.just(
                AgentEvent.meta("run-1"),
                AgentEvent.status("run-1", "running", true),
                AgentEvent.token("run-1", "reply"),
                AgentEvent.done("run-1")));

        AgentConversationService service = service(memory, router, runtime);
        List<ServerSentEvent<ChatStreamEvent>> events = service.stream(
                        new ConversationRequest(7L, new ChatRequest(null, "hello"), null))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).isNotNull();
        assertThat(events).extracting(event -> event.data().type())
                .containsExactly("meta", "status", "token", "done");
        verify(memory).append(identity, MessageRole.USER, "hello");
        verify(memory).append(identity, MessageRole.ASSISTANT, "reply");
    }

    @Test
    void doesNotPersistAssistantWhenRuntimeCompletesWithoutDone() {
        ConversationMemory memory = mock(ConversationMemory.class);
        ConversationIdentity identity = new ConversationIdentity(7L, 11L, "session-1", "student");
        when(memory.open(7L, null, "hello")).thenReturn(identity);
        when(memory.recentHistory(identity)).thenReturn(new ConversationHistory(List.of()));
        RequestRouter router = mock(RequestRouter.class);
        when(router.decide(any(), any(), any())).thenReturn(RoutingDecision.ordinary("test"));
        MindCareAgentRuntime runtime = mock(MindCareAgentRuntime.class);
        when(runtime.run(any())).thenReturn(Flux.just(
                AgentEvent.meta("run-1"), AgentEvent.token("run-1", "partial")));

        AgentConversationService service = service(memory, router, runtime);
        List<ServerSentEvent<ChatStreamEvent>> events = service.stream(
                        new ConversationRequest(7L, new ChatRequest(null, "hello"), null))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).isNotNull();
        assertThat(events).extracting(event -> event.data().type())
                .containsExactly("meta", "token");
        org.mockito.Mockito.verify(memory, never()).append(identity, MessageRole.ASSISTANT, "partial");
    }

    private AgentConversationService service(
            ConversationMemory memory,
            RequestRouter router,
            MindCareAgentRuntime runtime
    ) {
        multimodalAgentProperties applicationProperties = new multimodalAgentProperties();
        MindCareAgentProperties agentProperties = new MindCareAgentProperties();
        return new AgentConversationService(
                applicationProperties,
                agentProperties,
                memory,
                new ConversationHistoryMapper(),
                router,
                mock(PsychologicalAssessmentService.class),
                mock(ReportLifecycle.class),
                new PrivacySanitizer(),
                mock(EvaluationTraceService.class),
                runtime);
    }
}
