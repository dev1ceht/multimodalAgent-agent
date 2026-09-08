package com.multimodalAgent.agent.service.memory;

import java.time.Instant;
import java.util.List;

public record MemoryProjectionBatch(
        Long userId,
        List<Fact> facts,
        List<Topic> topics,
        List<Membership> memberships,
        List<Relation> relations
) {
    public record Fact(Long id, Long sessionId, String content, Instant occurredAt) {}
    public record Topic(Long id, String key, String title, String summary) {}
    public record Membership(Long factId, Long topicId) {}
    public record Relation(Long sourceFactId, Long targetFactId, MemoryRelationType type, double confidence) {}
}
