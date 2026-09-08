package com.multimodalAgent.agent.service.memory;

public record MemoryGraphHit(
        Long factId, String content, MemoryRelationType relationType, int depth, String pathContext) {
    public MemoryGraphHit(Long factId, String content, MemoryRelationType relationType, int depth) {
        this(factId, content, relationType, depth, "");
    }
}
