package com.multimodalAgent.agent.agentruntime;
import com.multimodalAgent.agent.service.agentruntime.AgentBudgetPolicy;
import com.multimodalAgent.agent.service.agentruntime.AgentRequest;
import com.multimodalAgent.agent.service.agentruntime.AgentRunContext;
import com.multimodalAgent.agent.service.agentruntime.AgentToolPolicy;
import com.multimodalAgent.agent.service.agentruntime.AgentAnswerPolicy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.service.chat.ConversationHistory;
import com.multimodalAgent.agent.service.chat.ConversationIdentity;
import com.multimodalAgent.agent.service.routing.RoutingDecision;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentToolPolicyTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentToolPolicy policy = new AgentToolPolicy();

    @Test
    void validatesSchemaAndRejectsModelSuppliedIdentityFields() throws Exception {
        assertThat(policy.validate(
                AgentToolPolicy.SEARCH_KNOWLEDGE,
                objectMapper.readTree("{\"query\":\"sleep support\",\"topK\":2}"))
                .valid()).isTrue();
        assertThat(policy.validate(
                AgentToolPolicy.SEARCH_KNOWLEDGE,
                objectMapper.readTree("{\"query\":\"sleep\",\"userId\":99}"))
                .errorCode()).isEqualTo("unknown_tool_argument");
        assertThat(policy.validate(
                AgentToolPolicy.SEARCH_KNOWLEDGE,
                objectMapper.readTree("{\"query\":\"sleep\",\"topK\":5}"))
                .errorCode()).isEqualTo("invalid_top_k");
    }

    @Test
    void disablesLongTermMemoryForHighRiskAndRequiresConsent() {
        AgentBudgetPolicy budgetPolicy = new AgentBudgetPolicy(new com.multimodalAgent.agent.config.MindCareAgentProperties());
        AgentRequest highRequest = new AgentRequest(
                new ConversationIdentity(7L, 8L, "session", "student"),
                "I need help",
                new ConversationHistory(List.of()),
                null,
                RoutingDecision.highRisk("test"),
                "run-1",
                Instant.now().plusSeconds(30),
                true);
        AgentRunContext high = new AgentRunContext(highRequest, budgetPolicy.start(highRequest.deadline()));
        assertThat(policy.authorize(high, AgentToolPolicy.RECALL_MEMORY).errorCode())
                .isEqualTo("memory_disabled_for_high_risk");

        AgentRequest noConsent = new AgentRequest(
                new ConversationIdentity(7L, 8L, "session", "student"),
                "hello",
                new ConversationHistory(List.of()),
                null,
                RoutingDecision.ordinary("test"),
                "run-2",
                Instant.now().plusSeconds(30),
                false);
        AgentRunContext denied = new AgentRunContext(noConsent, budgetPolicy.start(noConsent.deadline()));
        assertThat(policy.authorize(denied, AgentToolPolicy.SEARCH_KNOWLEDGE).errorCode())
                .isEqualTo("consent_required");
        assertThat(policy.isAllowed("unknown_tool")).isFalse();
    }
}
