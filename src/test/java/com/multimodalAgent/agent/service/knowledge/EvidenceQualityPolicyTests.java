package com.multimodalAgent.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EvidenceQualityPolicyTests {

    private multimodalAgentProperties properties;
    private EvidenceQualityPolicy policy;

    @BeforeEach
    void setUp() {
        properties = new multimodalAgentProperties();
        properties.getKnowledge().setMinimumEvidenceScore(0.25);
        policy = new EvidenceQualityPolicy(properties);
    }

    @Test
    void rejectsEvidenceBelowTheConfiguredRelevanceThreshold() {
        SearchResult weakEvidence = new SearchResult(
                1L,
                "sleep.md",
                "Generic content.",
                0.24);

        assertThat(policy.accepts(List.of(weakEvidence))).isFalse();
    }

    @Test
    void acceptsTraceableNonBlankEvidenceAtOrAboveTheThreshold() {
        SearchResult usableEvidence = new SearchResult(
                1L,
                "sleep.md",
                "Sleep support guidance.",
                0.25);

        assertThat(policy.accepts(List.of(usableEvidence))).isTrue();
    }

    @Test
    void filtersUnusableEvidenceBeforeItCanReachTheAnswerContext() {
        SearchResult usableEvidence = new SearchResult(
                1L,
                "sleep.md",
                "Sleep support guidance.",
                0.25);
        SearchResult weakEvidence = new SearchResult(
                2L,
                "other.md",
                "Generic content.",
                0.1);

        assertThat(policy.usableEvidence(List.of(usableEvidence, weakEvidence)))
                .containsExactly(usableEvidence);
    }
}
