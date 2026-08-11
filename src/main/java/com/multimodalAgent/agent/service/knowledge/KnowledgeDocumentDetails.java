package com.multimodalAgent.agent.service.knowledge;

import java.time.Instant;

/** Full editable administrative view of a canonical knowledge document. */
public record KnowledgeDocumentDetails(
        Long id,
        String source,
        String content,
        int characterCount,
        int chunkCount,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}
