package com.multimodalAgent.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgenticRagResultTests {

    @Test
    void contextAssignsStableEvidenceIdsInOrder() {
        AgenticRagResult result = new AgenticRagResult(
                "sleep support",
                List.of("sleep support"),
                List.of(
                        new SearchResult(1L, "sleep.md", "Sleep guidance.", 0.9),
                        new SearchResult(2L, "campus.md", "Campus support.", 0.8)),
                "evidence is sufficient",
                true);

        assertThat(result.contextBlock())
                .contains("[E1 | sleep.md | score 0.900]")
                .contains("[E2 | campus.md | score 0.800]");
    }
}
