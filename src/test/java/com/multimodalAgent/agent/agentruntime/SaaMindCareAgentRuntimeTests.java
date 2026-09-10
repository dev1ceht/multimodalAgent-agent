package com.multimodalAgent.agent.agentruntime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.MindCareAgentProperties;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.dto.StudentSupportStatusResponse;
import com.multimodalAgent.agent.service.agentruntime.AgentAnswerPolicy;
import com.multimodalAgent.agent.service.agentruntime.AgentBudgetPolicy;
import com.multimodalAgent.agent.service.agentruntime.AgentEvent;
import com.multimodalAgent.agent.service.agentruntime.AgentRequest;
import com.multimodalAgent.agent.service.agentruntime.AgentToolPolicy;
import com.multimodalAgent.agent.service.agentruntime.SaaMindCareAgentRuntime;
import com.multimodalAgent.agent.service.agentruntime.tools.KnowledgeSearchTool;
import com.multimodalAgent.agent.service.agentruntime.tools.MemoryRecallTool;
import com.multimodalAgent.agent.service.agentruntime.tools.SupportStatusProvider;
import com.multimodalAgent.agent.service.agentruntime.tools.SupportStatusTool;
import com.multimodalAgent.agent.service.chat.ConversationHistory;
import com.multimodalAgent.agent.service.chat.ConversationIdentity;
import com.multimodalAgent.agent.service.knowledge.EvidenceProvenance;
import com.multimodalAgent.agent.service.knowledge.EvidenceQualityPolicy;
import com.multimodalAgent.agent.service.knowledge.SearchResult;
import com.multimodalAgent.agent.service.knowledge.retrieval.EvidenceRetriever;
import com.multimodalAgent.agent.service.knowledge.retrieval.RetrievalResult;
import com.multimodalAgent.agent.service.memory.LongTermMemoryRecall;
import com.multimodalAgent.agent.service.memory.LongTermMemoryRetriever;
import com.multimodalAgent.agent.service.routing.RoutingDecision;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class SaaMindCareAgentRuntimeTests {

    @Test
    void modelSelectedKnowledgeToolResultReturnsThroughTheRealReactAgentLoop() {
        multimodalAgentProperties applicationProperties = new multimodalAgentProperties();
        applicationProperties.getKnowledge().setTopK(4);
        EvidenceRetriever retriever = query -> RetrievalResult.ready(
                "fake-knowledge",
                List.of(new SearchResult(
                        9L,
                        "sleep-guide",
                        "保持稳定的睡眠时间有助于逐步改善作息。",
                        0.92,
                        new EvidenceProvenance("v1", "vector-9", 0))));
        RuntimeFixture fixture = fixture(retriever, new ScriptedModel(true));

        List<AgentEvent> events = fixture.runtime.run(request(RoutingDecision.ordinary("chat")))
                .collectList()
                .block(Duration.ofSeconds(10));
        assertThat(events).isNotNull();
        assertThat(events).extracting(AgentEvent::type)
                .containsSubsequence(AgentEvent.TOOL_START, AgentEvent.TOOL_RESULT,
                        AgentEvent.TOKEN, AgentEvent.DONE);
        assertThat(events.stream().filter(event -> event.type().equals(AgentEvent.TOOL_RESULT))
                .findFirst().orElseThrow().content()).doesNotContain("sleep");
        assertThat(events.stream().filter(event -> event.type().equals(AgentEvent.TOKEN))
                .findFirst().orElseThrow().content()).contains("E1");
    }

    @Test
    void nonNoneRiskGetsAJavaPolicyEnforcedKnowledgeAttemptWhenModelEndsEarly() {
        AtomicInteger calls = new AtomicInteger();
        EvidenceRetriever retriever = query -> {
            calls.incrementAndGet();
            return RetrievalResult.ready(
                    "fake-knowledge",
                    List.of(new SearchResult(10L, "safety-guide", "联系可信任的支持人员。", 0.9)));
        };
        RuntimeFixture fixture = fixture(retriever, new ScriptedModel(false));

        List<AgentEvent> events = fixture.runtime.run(request(
                        new RoutingDecision(true, RiskLevel.LOW, 1.0, "risk floor")))
                .collectList()
                .block(Duration.ofSeconds(10));
        assertThat(events).isNotNull();
        assertThat(calls).hasValue(1);
        assertThat(events.stream().anyMatch(event -> event.type().equals(AgentEvent.STATUS)
                && event.policyEnforced())).isTrue();
        assertThat(events.get(events.size() - 1).type()).isEqualTo(AgentEvent.DONE);
    }

    private static RuntimeFixture fixture(EvidenceRetriever retriever, ChatModel model) {
        multimodalAgentProperties applicationProperties = new multimodalAgentProperties();
        applicationProperties.getKnowledge().setTopK(4);
        applicationProperties.getKnowledge().setMinimumEvidenceScore(0.2);
        MindCareAgentProperties agentProperties = new MindCareAgentProperties();
        agentProperties.setTimeout(Duration.ofSeconds(5));
        agentProperties.setToolTimeout(Duration.ofSeconds(2));
        agentProperties.setMaxModelCalls(4);
        agentProperties.setMaxToolCalls(4);
        agentProperties.setMaxTotalToolResultChars(18000);
        AgentBudgetPolicy budgetPolicy = new AgentBudgetPolicy(agentProperties);
        AgentToolPolicy toolPolicy = new AgentToolPolicy();
        ObjectMapper mapper = new ObjectMapper();
        KnowledgeSearchTool knowledge = new KnowledgeSearchTool(
                retriever,
                new EvidenceQualityPolicy(applicationProperties),
                toolPolicy,
                budgetPolicy,
                mapper,
                applicationProperties,
                agentProperties);
        LongTermMemoryRetriever memoryRetriever = query -> LongTermMemoryRecall.empty(
                LongTermMemoryRecall.Status.DISABLED, "disabled in test");
        MemoryRecallTool memory = new MemoryRecallTool(
                memoryRetriever,
                toolPolicy,
                budgetPolicy,
                mapper,
                applicationProperties,
                agentProperties);
        SupportStatusProvider support = identity -> List.<StudentSupportStatusResponse>of();
        SupportStatusTool supportTool = new SupportStatusTool(
                support,
                toolPolicy,
                budgetPolicy,
                mapper,
                agentProperties);
        SaaMindCareAgentRuntime runtime = new SaaMindCareAgentRuntime(
                model,
                agentProperties,
                budgetPolicy,
                toolPolicy,
                new AgentAnswerPolicy(),
                knowledge,
                memory,
                supportTool);
        return new RuntimeFixture(runtime);
    }

    private static AgentRequest request(RoutingDecision routing) {
        return new AgentRequest(
                new ConversationIdentity(7L, 8L, "session-public", "student"),
                "What can help with sleep?",
                new ConversationHistory(List.of()),
                null,
                routing,
                "run-test",
                Instant.now().plusSeconds(20),
                true);
    }

    private record RuntimeFixture(SaaMindCareAgentRuntime runtime) {
    }

    private static final class ScriptedModel implements ChatModel {
        private final boolean useTool;
        private final AtomicInteger calls = new AtomicInteger();

        private ScriptedModel(boolean useTool) {
            this.useTool = useTool;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            boolean hasToolResult = prompt.getInstructions().stream()
                    .anyMatch(message -> message.getMessageType().getValue().equals("tool"));
            AssistantMessage answer = useTool && !hasToolResult
                    ? AssistantMessage.builder()
                            .content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall(
                                    "call-knowledge", "function", "search_knowledge",
                                    "{\"query\":\"sleep\",\"topK\":1}")))
                            .build()
                    : new AssistantMessage(useTool
                            ? "根据本轮资料（E1），可以先尝试保持稳定的睡眠时间。"
                            : "我会先听听你的具体情况。");
            return new ChatResponse(List.of(new Generation(answer)));
        }
    }
}