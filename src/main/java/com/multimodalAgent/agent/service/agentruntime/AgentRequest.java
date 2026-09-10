package com.multimodalAgent.agent.service.agentruntime;

import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.service.chat.ConversationHistory;
import com.multimodalAgent.agent.service.chat.ConversationIdentity;
import com.multimodalAgent.agent.service.multimodal.MultimodalAnalysis;
import com.multimodalAgent.agent.service.routing.RoutingDecision;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable, trusted input snapshot for one Agent run. */
public record AgentRequest(
        ConversationIdentity identity,
        String input,
        ConversationHistory previousHistory,
        MultimodalAnalysis multimodalAnalysis,
        RoutingDecision routing,
        String runId,
        Instant deadline,
        boolean consentValidated
) {

    public AgentRequest {
        identity = Objects.requireNonNull(identity, "identity");
        input = requireInput(input);
        previousHistory = previousHistory == null
                ? new ConversationHistory(java.util.List.of())
                : previousHistory;
        routing = routing == null ? RoutingDecision.ordinary("未提供 Agent 路由") : routing;
        runId = runId == null || runId.isBlank() ? UUID.randomUUID().toString() : runId.trim();
        deadline = deadline == null ? Instant.now().plus(Duration.ofSeconds(60)) : deadline;
    }

    public AgentRequest(
            ConversationIdentity identity,
            String input,
            ConversationHistory previousHistory,
            RoutingDecision routing,
            String runId,
            Instant deadline
    ) {
        this(identity, input, previousHistory, null, routing, runId, deadline, true);
    }

    public AgentRequest(
            ConversationIdentity identity,
            String input,
            ConversationHistory previousHistory,
            MultimodalAnalysis multimodalAnalysis,
            RoutingDecision routing,
            String runId,
            Instant deadline
    ) {
        this(identity, input, previousHistory, multimodalAnalysis, routing, runId, deadline, true);
    }

    public RiskLevel riskLevel() {
        return routing.riskLevel();
    }

    public Duration remaining() {
        return Duration.between(Instant.now(), deadline);
    }

    private static String requireInput(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Agent input must not be blank.");
        }
        return value.trim();
    }
}
