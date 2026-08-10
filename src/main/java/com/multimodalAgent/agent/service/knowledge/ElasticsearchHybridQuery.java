package com.multimodalAgent.agent.service.knowledge;

import java.util.List;

/** Complete Elasticsearch hybrid retrieval request hidden behind the EvidenceRetriever seam. */
public record ElasticsearchHybridQuery(
        String indexName,
        String queryText,
        List<Double> queryVector,
        int knnK,
        int knnNumCandidates,
        int rankWindowSize,
        int rankConstant,
        int resultSize
) {

    public ElasticsearchHybridQuery {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("Elasticsearch index name is required.");
        }
        if (queryText == null || queryText.isBlank()) {
            throw new IllegalArgumentException("Elasticsearch query text is required.");
        }
        queryVector = queryVector == null ? List.of() : List.copyOf(queryVector);
        if (queryVector.isEmpty()) {
            throw new IllegalArgumentException("Elasticsearch query vector is required.");
        }
        if (knnK <= 0 || knnNumCandidates < knnK
                || rankWindowSize <= 0 || rankConstant < 0 || resultSize <= 0) {
            throw new IllegalArgumentException("Elasticsearch hybrid retrieval parameters are invalid.");
        }
        indexName = indexName.trim().toLowerCase(java.util.Locale.ROOT);
        queryText = queryText.trim();
    }
}
