package com.multimodalAgent.agent.service.knowledge;

import java.util.List;
import java.util.Optional;

/** Deterministic parent/child projection of one knowledge document. */
public record ChunkPlan(String source, String strategy, List<ParentChunk> parents) {

    public ChunkPlan {
        parents = parents == null ? List.of() : List.copyOf(parents);
    }

    public List<ChildChunk> children() {
        return parents.stream().flatMap(parent -> parent.children().stream()).toList();
    }

    public Optional<ParentChunk> parent(String parentKey) {
        return parents.stream().filter(parent -> parent.parentKey().equals(parentKey)).findFirst();
    }
}
