package com.multimodalAgent.agent.service.memory;

public record MemoryGraphHit(Long factId, String content, MemoryRelationType relationType, int depth) {
}
