package com.multimodalAgent.agent.service.memory;

public record LongTermMemoryQuery(Long userId, Long sessionId, String text) {
}
