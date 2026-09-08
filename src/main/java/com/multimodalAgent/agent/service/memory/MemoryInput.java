package com.multimodalAgent.agent.service.memory;

import com.multimodalAgent.agent.domain.MessageRole;
import java.time.Instant;

/** Persisted conversation message accepted by the long-term-memory module. */
public record MemoryInput(
        Long userId,
        Long sessionId,
        String sessionPublicId,
        Long messageId,
        MessageRole role,
        String content,
        Instant occurredAt
) {
    public MemoryInput {
        if (userId == null || sessionId == null || messageId == null) {
            throw new IllegalArgumentException("Persisted memory identity is required.");
        }
        if (sessionPublicId == null || sessionPublicId.isBlank()) {
            throw new IllegalArgumentException("Session public id is required.");
        }
        if (role == null || content == null || content.isBlank() || occurredAt == null) {
            throw new IllegalArgumentException("Memory content, role and occurrence time are required.");
        }
        sessionPublicId = sessionPublicId.trim();
        content = content.trim();
    }
}
