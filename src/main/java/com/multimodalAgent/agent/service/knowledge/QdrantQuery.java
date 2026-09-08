package com.multimodalAgent.agent.service.knowledge;

import java.util.List;

/** Dense-vector Qdrant request hidden behind the EvidenceRetriever seam. */
public record QdrantQuery(
        String indexName,
        String queryText,
        List<Double> queryVector,
        int resultSize
) {

    public QdrantQuery {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("Qdrant index name is required.");
        }
        if (queryText == null || queryText.isBlank()) {
            throw new IllegalArgumentException("Qdrant query text is required.");
        }
        queryVector = queryVector == null ? List.of() : List.copyOf(queryVector);
        if (queryVector.isEmpty()) {
            throw new IllegalArgumentException("Qdrant query vector is required.");
        }
        if (resultSize <= 0) {
            throw new IllegalArgumentException("Qdrant result size must be positive.");
        }
        indexName = indexName.trim().toLowerCase(java.util.Locale.ROOT);
        queryText = queryText.trim();
    }
}
