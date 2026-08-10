package com.multimodalAgent.agent.service.knowledge.retrieval;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.service.knowledge.SearchResult;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Lightweight production reranker combining the vector score with query-term coverage.
 *
 * <p>It deliberately does not call another model. This makes ranking deterministic and keeps the
 * post-fusion stage deterministic while leaving a narrow seam for a cross-encoder reranker.</p>
 */
@Component
public class HybridEvidenceReranker implements EvidenceReranker {

    private static final Pattern QUERY_TERM_SEPARATOR = Pattern.compile("[\\s,，。！？；：.!?;:]+");

    private final multimodalAgentProperties properties;

    public HybridEvidenceReranker(multimodalAgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<SearchResult> rerank(String query, List<SearchResult> candidates, int limit) {
        if (limit <= 0 || candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        double semanticWeight = nonNegativeFinite(properties.getKnowledge().getRerankSemanticWeight());
        double keywordWeight = nonNegativeFinite(properties.getKnowledge().getRerankKeywordWeight());
        double totalWeight = semanticWeight + keywordWeight;
        if (totalWeight == 0.0) {
            return candidates.stream()
                    .filter(result -> result != null)
                    .limit(limit)
                    .toList();
        }

        return java.util.stream.IntStream.range(0, candidates.size())
                .mapToObj(index -> new ScoredCandidate(
                        candidates.get(index),
                        combinedScore(
                                query,
                                candidates.get(index),
                                semanticWeight,
                                keywordWeight,
                                totalWeight),
                        index))
                .filter(candidate -> candidate.result() != null)
                .sorted(Comparator
                        .comparingDouble(ScoredCandidate::score)
                        .reversed()
                        .thenComparingInt(ScoredCandidate::originalIndex))
                .limit(limit)
                .map(candidate -> candidate.result().withScore(candidate.score()))
                .toList();
    }

    private double combinedScore(
            String query,
            SearchResult candidate,
            double semanticWeight,
            double keywordWeight,
            double totalWeight
    ) {
        if (candidate == null) {
            return 0.0;
        }
        double semanticScore = normalize(candidate.score());
        double keywordCoverage = keywordCoverage(query, candidate.content());
        return (semanticScore * semanticWeight + keywordCoverage * keywordWeight) / totalWeight;
    }

    private double normalize(double score) {
        if (!Double.isFinite(score)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, score));
    }

    private double nonNegativeFinite(double value) {
        return Double.isFinite(value) ? Math.max(0.0, value) : 0.0;
    }

    private double keywordCoverage(String query, String content) {
        if (query == null || query.isBlank() || content == null || content.isBlank()) {
            return 0.0;
        }
        String normalizedContent = content.toLowerCase(Locale.ROOT);
        List<String> terms = Arrays.stream(QUERY_TERM_SEPARATOR.split(query.toLowerCase(Locale.ROOT)))
                .filter(term -> term.length() >= 2)
                .distinct()
                .toList();
        if (terms.isEmpty()) {
            return 0.0;
        }
        long matched = terms.stream()
                .filter(normalizedContent::contains)
                .count();
        return matched / (double) terms.size();
    }

    private record ScoredCandidate(SearchResult result, double score, int originalIndex) {
    }
}
