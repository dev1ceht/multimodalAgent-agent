package com.multimodalAgent.agent.service.knowledge;

import java.util.List;

/** Coherent section returned to the answer model after one of its children is retrieved. */
public record ParentChunk(
        String parentKey,
        String sectionPath,
        String content,
        int startOffset,
        int endOffset,
        Integer pageStart,
        Integer pageEnd,
        List<ChildChunk> children
) {

    public ParentChunk {
        children = children == null ? List.of() : List.copyOf(children);
    }
}
