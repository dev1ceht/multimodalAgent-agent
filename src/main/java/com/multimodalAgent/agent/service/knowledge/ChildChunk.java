package com.multimodalAgent.agent.service.knowledge;

/** Small searchable slice whose embedding and lexical text are stored in the retrieval index. */
public record ChildChunk(
        String parentKey,
        int childIndex,
        String content,
        String searchText,
        int startOffset,
        int endOffset,
        Integer pageStart,
        Integer pageEnd
) {
}
