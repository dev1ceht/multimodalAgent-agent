package com.multimodalAgent.agent.agentruntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.config.MindCareAgentProperties;
import com.multimodalAgent.agent.domain.AgentRunRecord;
import com.multimodalAgent.agent.domain.AgentToolExecutionRecord;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.repository.AgentRunRepository;
import com.multimodalAgent.agent.repository.AgentToolExecutionRepository;
import com.multimodalAgent.agent.service.agentruntime.AgentEvent;
import com.multimodalAgent.agent.service.agentruntime.AgentRequest;
import com.multimodalAgent.agent.service.agentruntime.AgentRunPersistenceService;
import com.multimodalAgent.agent.service.chat.ConversationHistory;
import com.multimodalAgent.agent.service.chat.ConversationIdentity;
import com.multimodalAgent.agent.service.routing.RoutingDecision;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentRunPersistenceServiceTests {

    @Test
    void recordsBoundedLifecycleCountersAndNeverNeedsRawPromptData() {
        AgentRunRepository runs = mock(AgentRunRepository.class);
        AgentToolExecutionRepository tools = mock(AgentToolExecutionRepository.class);
        AgentRunPersistenceService persistence = new AgentRunPersistenceService(runs, tools);
        AgentRequest request = new AgentRequest(
                new ConversationIdentity(7L, 11L, "session-1", "student"),
                "student text must not be persisted here",
                new ConversationHistory(java.util.List.of()),
                new RoutingDecision(true, RiskLevel.LOW, 1.0, "test"),
                "run-1",
                Instant.now().plusSeconds(30));
        AgentRunRecord run = new AgentRunRecord();
        run.setRunId("run-1");
        when(runs.findById("run-1")).thenReturn(Optional.empty(), Optional.of(run));
        AgentToolExecutionRecord tool = new AgentToolExecutionRecord();
        when(tools.findFirstByRunIdAndFinishedAtIsNullOrderByStartedAtDesc("run-1"))
                .thenReturn(Optional.of(tool));

        persistence.start(request, "saa", "model-x", "mindcare-agent-v1");
        persistence.recordEvent(AgentEvent.status("run-1", "started", true));
        persistence.recordEvent(AgentEvent.modelCall("run-1"));
        persistence.recordEvent(AgentEvent.toolStart("run-1", "search_knowledge"));
        persistence.recordEvent(AgentEvent.toolResult("run-1", "search_knowledge", "denied", "denied:tool_not_allowed"));
        persistence.finish("run-1", AgentRunPersistenceService.ERROR, "tool_call_budget_exceeded", true);

        int modelCalls = run.getModelCallCount();
        Instant finishedAt = run.getFinishedAt();
        persistence.recordEvent(AgentEvent.modelCall("run-1"));
        persistence.finish("run-1", AgentRunPersistenceService.SUCCESS, null, false);

        assertThat(run.getStatus()).isEqualTo(AgentRunPersistenceService.ERROR);
        assertThat(run.getModelCallCount()).isEqualTo(modelCalls);
        assertThat(run.getFinishedAt()).isEqualTo(finishedAt);

        assertThat(run.getModelCallCount()).isOne();
        assertThat(run.getToolCallCount()).isOne();
        assertThat(run.getToolRejectionCount()).isOne();
        assertThat(run.isBudgetTerminated()).isTrue();
        assertThat(tool.getFinishedAt()).isNotNull();
        assertThat(tool.getErrorCode()).isEqualTo("tool_not_allowed");
        verify(runs, atLeastOnce()).save(any(AgentRunRecord.class));
    }
    @Test
    void cleansAgentMetadataUsingConfiguredRetentionWindow() {
        AgentRunRepository runs = mock(AgentRunRepository.class);
        AgentToolExecutionRepository tools = mock(AgentToolExecutionRepository.class);
        MindCareAgentProperties properties = new MindCareAgentProperties();
        properties.setMetadataRetention(Duration.ofDays(3));
        AgentRunPersistenceService persistence = new AgentRunPersistenceService(
                runs, tools, null, properties);

        persistence.cleanupExpiredMetadata();

        verify(tools).deleteByStartedAtBefore(any(Instant.class));
        verify(runs).deleteByFinishedAtBefore(any(Instant.class));
    }
}
