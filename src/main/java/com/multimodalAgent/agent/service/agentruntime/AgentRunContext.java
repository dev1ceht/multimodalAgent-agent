package com.multimodalAgent.agent.service.agentruntime;

import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.service.chat.ConversationIdentity;
import com.multimodalAgent.agent.service.knowledge.SearchResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Mutable run-local state; identity and evidence never come from model arguments. */
public final class AgentRunContext {

    private final ConversationIdentity identity;
    private final RiskLevel riskLevel;
    private final String runId;
    private final Instant deadline;
    private final boolean consentValidated;
    private final AgentBudgetPolicy.Budget budget;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean knowledgeAttempted = new AtomicBoolean();
    private final AtomicInteger evidenceSequence = new AtomicInteger();
    private final Map<String, SearchResult> evidence = new ConcurrentHashMap<>();
    private final List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());

    public AgentRunContext(AgentRequest request, AgentBudgetPolicy.Budget budget) {
        identity = request.identity();
        riskLevel = request.riskLevel();
        runId = request.runId();
        deadline = request.deadline();
        consentValidated = request.consentValidated();
        this.budget = budget;
    }

    public ConversationIdentity identity() {
        return identity;
    }

    public RiskLevel riskLevel() {
        return riskLevel;
    }

    public String runId() {
        return runId;
    }

    public Instant deadline() {
        return deadline;
    }

    public boolean consentValidated() {
        return consentValidated;
    }

    public AgentBudgetPolicy.Budget budget() {
        return budget;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        cancelled.set(true);
        if (budget != null) {
            budget.cancel();
        }
    }

    public Duration remaining() {
        return Duration.between(Instant.now(), deadline);
    }

    public boolean isDeadlineExceeded() {
        return remaining().isZero() || remaining().isNegative();
    }

    public void markKnowledgeAttempted() {
        knowledgeAttempted.set(true);
    }

    public boolean knowledgeAttempted() {
        return knowledgeAttempted.get();
    }

    public String registerEvidence(SearchResult result) {
        String evidenceId = "E" + evidenceSequence.incrementAndGet();
        evidence.put(evidenceId, result);
        return evidenceId;
    }

    public boolean hasEvidence(String evidenceId) {
        return evidence.containsKey(evidenceId);
    }

    public Map<String, SearchResult> evidenceSnapshot() {
        return new LinkedHashMap<>(evidence);
    }

    public void addEvent(AgentEvent event) {
        if (!isCancelled()) {
            events.add(event);
        }
    }

    public List<AgentEvent> eventsSnapshot() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }
}
