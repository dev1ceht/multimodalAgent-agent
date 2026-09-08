package com.multimodalAgent.agent.service.memory;

import java.time.Instant;

public record MemoryVectorHit(
        Long id, String content, double score, Long sessionId, Instant occurredAt, Long projectionRevision) {
    public MemoryVectorHit(Long id, String content, double score, Long sessionId, Instant occurredAt) {
        this(id, content, score, sessionId, occurredAt, null);
    }
}
