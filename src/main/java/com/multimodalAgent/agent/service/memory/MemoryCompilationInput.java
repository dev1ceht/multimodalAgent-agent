package com.multimodalAgent.agent.service.memory;

public record MemoryCompilationInput(
        Long userId,
        Long sessionId,
        Long messageId,
        String content,
        String existingFacts,
        String occurredAt
) {
}
