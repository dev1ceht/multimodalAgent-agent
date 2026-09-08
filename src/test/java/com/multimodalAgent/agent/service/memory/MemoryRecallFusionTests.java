package com.multimodalAgent.agent.service.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryRecallFusionTests {
    @Test
    void rrfKeepsKeywordOnlyFactsAndRewardsCandidatesFoundByBothChannels() {
        List<MemoryRecallFusion.Hit> hits = MemoryRecallFusion.fuse(
                List.of(vector(1L, 0.9), vector(2L, 0.8)),
                List.of(new MemoryKeywordHit(3L, 5.0), new MemoryKeywordHit(1L, 2.0)),
                8, 0.3, "rrf");

        assertThat(hits).extracting(MemoryRecallFusion.Hit::factId)
                .containsExactly(1L, 2L, 3L);
        assertThat(hits.get(0).sources()).containsExactly("vector", "bm25");
        assertThat(hits.stream().filter(hit -> hit.factId().equals(3L)).findFirst().orElseThrow().sources())
                .containsExactly("bm25");
        assertThat(hits.get(0).score()).isEqualTo(1.0);
    }

    @Test
    void weightedFusionUsesNormalizedChannelScores() {
        List<MemoryRecallFusion.Hit> hits = MemoryRecallFusion.fuse(
                List.of(vector(1L, 1.0), vector(2L, 0.5)),
                List.of(new MemoryKeywordHit(2L, 10.0)),
                8, 0.6, "weighted");

        assertThat(hits).extracting(MemoryRecallFusion.Hit::factId)
                .containsExactly(2L, 1L);
    }

    private MemoryVectorHit vector(Long id, double score) {
        return new MemoryVectorHit(id, "fact-" + id, score, 1L, Instant.EPOCH);
    }
}
