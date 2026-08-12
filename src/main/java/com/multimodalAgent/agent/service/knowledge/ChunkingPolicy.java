package com.multimodalAgent.agent.service.knowledge;

/** Immutable parameters that are recorded with a knowledge version. */
public record ChunkingPolicy(
        String strategy,
        int parentMaxSize,
        int childMinSize,
        int childTargetSize,
        int childMaxSize,
        int childOverlap
) {

    public ChunkingPolicy {
        strategy = strategy == null || strategy.isBlank() ? "HIERARCHICAL_V1" : strategy.trim();
        parentMaxSize = Math.max(240, parentMaxSize);
        childMinSize = Math.max(40, childMinSize);
        childTargetSize = Math.max(childMinSize, childTargetSize);
        childMaxSize = Math.max(childTargetSize, childMaxSize);
        childOverlap = Math.max(0, Math.min(childOverlap, childTargetSize / 2));
    }

    public static ChunkingPolicy hierarchicalDefaults() {
        return new ChunkingPolicy("HIERARCHICAL_V1", 900, 120, 240, 320, 40);
    }
}
