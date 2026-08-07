package com.multimodalAgent.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EvidenceProvenanceTests {

    @Test
    void backwardCompatibleSearchResultsCarryUnknownProvenance() {
        SearchResult result = new SearchResult(1L, "sleep.md", "Sleep support.", 0.8);

        assertThat(result.provenance()).isEqualTo(EvidenceProvenance.unknown());
    }

    @Test
    void scoreAndContentTransformsPreserveTheEvidenceProvenance() {
        EvidenceProvenance provenance = new EvidenceProvenance("version-1", "vector-1", 2);
        SearchResult result = new SearchResult(
                1L,
                "sleep.md",
                "Sleep support.",
                0.8,
                provenance);

        assertThat(result.withScore(0.7).provenance()).isEqualTo(provenance);
        assertThat(result.withContent("Expanded sleep support.").provenance())
                .isEqualTo(provenance);
    }
}
