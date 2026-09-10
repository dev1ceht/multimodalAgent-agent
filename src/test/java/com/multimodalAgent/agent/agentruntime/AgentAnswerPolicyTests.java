package com.multimodalAgent.agent.agentruntime;
import com.multimodalAgent.agent.service.agentruntime.AgentBudgetPolicy;
import com.multimodalAgent.agent.service.agentruntime.AgentRequest;
import com.multimodalAgent.agent.service.agentruntime.AgentRunContext;
import com.multimodalAgent.agent.service.agentruntime.AgentToolPolicy;
import com.multimodalAgent.agent.service.agentruntime.AgentAnswerPolicy;

import static org.assertj.core.api.Assertions.assertThat;

import com.multimodalAgent.agent.config.MindCareAgentProperties;
import com.multimodalAgent.agent.service.chat.ConversationHistory;
import com.multimodalAgent.agent.service.chat.ConversationIdentity;
import com.multimodalAgent.agent.service.knowledge.SearchResult;
import com.multimodalAgent.agent.service.routing.RoutingDecision;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentAnswerPolicyTests {

    @Test
    void onlyAcceptsCitationsRegisteredByTheCurrentRun() {
        MindCareAgentProperties properties = new MindCareAgentProperties();
        AgentBudgetPolicy budgetPolicy = new AgentBudgetPolicy(properties);
        AgentRequest request = new AgentRequest(
                new ConversationIdentity(1L, 2L, "session", "student"),
                "What helps with sleep?",
                new ConversationHistory(List.of()),
                RoutingDecision.ordinary("test"),
                "run-1",
                Instant.now().plusSeconds(30));
        AgentRunContext context = new AgentRunContext(request, budgetPolicy.start(request.deadline()));
        context.registerEvidence(new SearchResult(4L, "sleep-guide", "Keep a regular sleep schedule.", 0.9));
        AgentAnswerPolicy policy = new AgentAnswerPolicy();

        assertThat(policy.validate(context, "Try a regular schedule (E1).").accepted()).isTrue();
        assertThat(policy.validate(context, "Try this (E2).").errorCode()).isEqualTo("invalid_citation");
        assertThat(policy.validate(context, "tool_call recipient: staff@example.com").errorCode())
                .isEqualTo("internal_content");
        assertThat(policy.validate(context, " ").errorCode()).isEqualTo("empty_final");
    }
}
