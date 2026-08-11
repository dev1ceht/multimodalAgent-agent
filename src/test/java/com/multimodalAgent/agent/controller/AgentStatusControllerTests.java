package com.multimodalAgent.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.KnowledgeVersionStatus;
import com.multimodalAgent.agent.service.knowledge.KnowledgePublicationStatus;
import com.multimodalAgent.agent.service.knowledge.KnowledgeService;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentStatusControllerTests {

    @Test
    void exposesCurrentNonSensitiveEvaluationConfiguration() {
        multimodalAgentProperties properties = new multimodalAgentProperties();
        properties.getAi().setTemperature(0.42);
        properties.getAi().setMaxTokens(321);
        properties.getAi().setContextWindow(8192);
        properties.getEmbedding().setModel("current-embedding");
        properties.getEmbedding().setDimensions(768);
        properties.getEmbedding().setApiKey("must-not-be-exposed");
        properties.getKnowledge().setTopK(7);
        properties.getKnowledge().setChunkSize(640);
        properties.getKnowledge().setChunkOverlap(96);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        when(knowledgeService.publicationStatus()).thenReturn(new KnowledgePublicationStatus(
                "active-v3",
                KnowledgeVersionStatus.ACTIVE,
                "active-v3",
                KnowledgeVersionStatus.ACTIVE,
                null,
                0,
                null,
                10,
                100,
                Instant.parse("2026-08-11T00:00:00Z"),
                Instant.parse("2026-08-11T00:01:00Z"),
                true));

        AgentStatusController.AgentStatusResponse status =
                new AgentStatusController(properties, knowledgeService).status();

        assertThat(status.generation().temperature()).isEqualTo(0.42);
        assertThat(status.generation().maxTokens()).isEqualTo(321);
        assertThat(status.generation().contextWindow()).isEqualTo(8192);
        assertThat(status.embedding().model()).isEqualTo("current-embedding");
        assertThat(status.embedding().dimensions()).isEqualTo(768);
        assertThat(status.retrieval().topK()).isEqualTo(7);
        assertThat(status.retrieval().chunkSize()).isEqualTo(640);
        assertThat(status.retrieval().chunkOverlap()).isEqualTo(96);
        assertThat(status.knowledge().activeVersionKey()).isEqualTo("active-v3");
        assertThat(status.knowledge().retrievalReady()).isTrue();
        assertThat(status.toString()).doesNotContain("must-not-be-exposed");
    }
}
