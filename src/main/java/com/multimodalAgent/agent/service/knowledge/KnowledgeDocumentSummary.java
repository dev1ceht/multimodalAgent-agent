package com.multimodalAgent.agent.service.knowledge;

/** Compact administrative view of a canonical knowledge document. */
public record KnowledgeDocumentSummary(
        Long id,
        String source,
        int characterCount,
        String preview,
        long version
) {
}
