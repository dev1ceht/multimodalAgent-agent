package com.multimodalAgent.agent.service.knowledge;

import java.util.List;

/** Bounded page of canonical knowledge document summaries. */
public record KnowledgeDocumentPage(
        List<KnowledgeDocumentSummary> documents,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
