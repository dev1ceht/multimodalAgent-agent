package com.multimodalAgent.agent.service.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.service.knowledge.SearchResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HybridEvidenceRerankerTests {

    private multimodalAgentProperties properties;
    private HybridEvidenceReranker reranker;

    @BeforeEach
    void setUp() {
        properties = new multimodalAgentProperties();
        properties.getKnowledge().setRerankSemanticWeight(0.5);
        properties.getKnowledge().setRerankKeywordWeight(0.5);
        reranker = new HybridEvidenceReranker(properties);
    }

    @Test
    void keywordCoverageCanPromoteARelevantCandidateOverAWeakSemanticHit() {
        SearchResult semanticHit = new SearchResult(
                1L,
                "general.md",
                "General wellness guidance.",
                0.95);
        SearchResult keywordHit = new SearchResult(
                2L,
                "sleep.md",
                "Sleep support guidance recommends a consistent sleep routine.",
                0.65);

        List<SearchResult> ranked = reranker.rerank(
                "sleep support",
                List.of(semanticHit, keywordHit),
                1);

        assertThat(ranked).extracting(SearchResult::chunkId).containsExactly(2L);
        assertThat(ranked.get(0).score()).isEqualTo(0.825);
    }

    @Test
    void preservesOriginalOrderForEqualScoresAndHonorsLimit() {
        SearchResult first = new SearchResult(1L, "one.md", "same content", 0.7);
        SearchResult second = new SearchResult(2L, "two.md", "same content", 0.7);

        List<SearchResult> ranked = reranker.rerank("unmatched query", List.of(first, second), 1);

        assertThat(ranked).extracting(SearchResult::chunkId).containsExactly(1L);
    }
}
