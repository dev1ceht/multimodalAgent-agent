package com.multimodalAgent.agent.service.agentruntime;

import com.multimodalAgent.agent.config.MindCareAgentProperties;
import com.multimodalAgent.agent.service.chat.ConversationIdentity;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Process-local session mutex for streaming runs.
 *
 * <p>The key contains the trusted database ids, never a model-provided identifier. Expiry makes
 * a forgotten lease recoverable after a client disconnect or process interruption; a shared
 * deployment can replace this component with a Redis/DB lease without changing the chat service.
 * </p>
 */
@Component
public final class AgentSessionLeaseService {

    private final MindCareAgentProperties properties;
    private final Map<String, Lease> active = new HashMap<>();

    public AgentSessionLeaseService(MindCareAgentProperties properties) {
        this.properties = properties;
    }

    public boolean tryAcquire(ConversationIdentity identity, String runId, Instant deadline) {
        if (identity == null || identity.userId() == null || identity.sessionId() == null
                || runId == null || runId.isBlank()) {
            return false;
        }
        Instant expiresAt = deadline == null ? defaultExpiry() : deadline;
        String key = key(identity);
        synchronized (active) {
            Lease existing = active.get(key);
            Instant now = Instant.now();
            if (existing != null && now.isBefore(existing.expiresAt())) {
                return false;
            }
            active.put(key, new Lease(runId.trim(), expiresAt));
            return true;
        }
    }

    public void release(ConversationIdentity identity, String runId) {
        if (identity == null || runId == null) {
            return;
        }
        synchronized (active) {
            Lease existing = active.get(key(identity));
            if (existing != null && runId.trim().equals(existing.runId())) {
                active.remove(key(identity));
            }
        }
    }

    int activeLeaseCount() {
        synchronized (active) {
            return active.size();
        }
    }

    private Instant defaultExpiry() {
        Duration timeout = properties.getTimeout();
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            timeout = Duration.ofSeconds(60);
        }
        return Instant.now().plus(timeout);
    }

    private String key(ConversationIdentity identity) {
        return identity.userId() + ":" + identity.sessionId();
    }

    private record Lease(String runId, Instant expiresAt) {
    }
}
