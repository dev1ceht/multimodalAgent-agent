package com.multimodalAgent.agent.service.memory;

public record CompiledRelation(String source, String target, MemoryRelationType type, double confidence) {
}
