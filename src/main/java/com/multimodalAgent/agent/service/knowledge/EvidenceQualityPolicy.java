package com.multimodalAgent.agent.service.knowledge;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Deterministic evidence gate applied after model-based RAG review.
 *
 * <p>The language model may assess coverage, but it cannot make an untraceable or clearly
 * irrelevant result usable. This policy keeps that hard rule local and independent of any
 * retrieval backend.</p>
 */
@Component
public final class EvidenceQualityPolicy {

    private final double minimumEvidenceScore;

    public EvidenceQualityPolicy(multimodalAgentProperties properties) {
        double configured = properties.getKnowledge().getMinimumEvidenceScore();
        minimumEvidenceScore = Double.isFinite(configured)
                ? Math.max(0.0, Math.min(1.0, configured))
                : 0.0;
    }

    public boolean accepts(List<SearchResult> evidence) {
        return !usableEvidence(evidence).isEmpty();
    }

    public List<SearchResult> usableEvidence(List<SearchResult> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        return evidence.stream()
                .filter(result -> result != null)
                .filter(this::isUsable)
                .toList();
    }

    private boolean isUsable(SearchResult result) {
        return result.source() != null
                && !result.source().isBlank()
                && result.content() != null
                && !result.content().isBlank()
                && Double.isFinite(result.score())
                && result.score() >= minimumEvidenceScore;
    }
}
