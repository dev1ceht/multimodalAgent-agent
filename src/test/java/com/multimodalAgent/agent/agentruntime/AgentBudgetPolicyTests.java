package com.multimodalAgent.agent.agentruntime;
import com.multimodalAgent.agent.service.agentruntime.AgentBudgetPolicy;
import com.multimodalAgent.agent.service.agentruntime.AgentRequest;
import com.multimodalAgent.agent.service.agentruntime.AgentRunContext;
import com.multimodalAgent.agent.service.agentruntime.AgentToolPolicy;
import com.multimodalAgent.agent.service.agentruntime.AgentAnswerPolicy;

import static org.assertj.core.api.Assertions.assertThat;

import com.multimodalAgent.agent.config.MindCareAgentProperties;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentBudgetPolicyTests {

    @Test
    void capsModelToolAndDuplicateCallsWithinOneRun() {
        MindCareAgentProperties properties = new MindCareAgentProperties();
        properties.setMaxModelCalls(2);
        properties.setMaxToolCalls(3);
        properties.setMaxIdenticalToolCalls(2);
        AgentBudgetPolicy policy = new AgentBudgetPolicy(properties);
        AgentBudgetPolicy.Budget budget = policy.start(Instant.now().plusSeconds(30));

        assertThat(policy.beforeModelCall(budget).allowed()).isTrue();
        assertThat(policy.beforeModelCall(budget).allowed()).isTrue();
        assertThat(policy.beforeModelCall(budget).errorCode()).isEqualTo("model_call_budget_exceeded");

        assertThat(policy.beforeToolCall(budget, "search_knowledge", "{\"query\":\"sleep\"}").allowed())
                .isTrue();
        assertThat(policy.beforeToolCall(budget, "search_knowledge", "{\"query\":\"sleep\"}").allowed())
                .isTrue();
        assertThat(policy.beforeToolCall(budget, "search_knowledge", "{\"query\":\"sleep\"}").errorCode())
                .isEqualTo("identical_tool_call_budget_exceeded");
    }

    @Test
    void stopsExpiredAndCancelledRunsBeforeExternalCalls() {
        MindCareAgentProperties properties = new MindCareAgentProperties();
        AgentBudgetPolicy policy = new AgentBudgetPolicy(properties);
        AgentBudgetPolicy.Budget expired = policy.start(Instant.now().minusSeconds(1));
        assertThat(policy.beforeModelCall(expired).errorCode()).isEqualTo("deadline_exceeded");

        AgentBudgetPolicy.Budget cancelled = policy.start(Instant.now().plusSeconds(30));
        policy.cancel(cancelled);
        assertThat(policy.beforeToolCall(cancelled, "search_knowledge", "{}").errorCode())
                .isEqualTo("cancelled");
    }
}
