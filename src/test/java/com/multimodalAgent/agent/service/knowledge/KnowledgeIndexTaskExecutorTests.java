package com.multimodalAgent.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.KnowledgeIndexTask;
import com.multimodalAgent.agent.domain.KnowledgeIndexTaskStatus;
import com.multimodalAgent.agent.domain.KnowledgeVersion;
import com.multimodalAgent.agent.domain.KnowledgeVersionDocument;
import com.multimodalAgent.agent.domain.KnowledgeVersionStatus;
import com.multimodalAgent.agent.repository.KnowledgeIndexTaskRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionChunkRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionDocumentRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

@DataJpaTest
@Import({KnowledgeIndexTaskExecutor.class, KnowledgeIndexTaskExecutorTests.TestConfig.class})
class KnowledgeIndexTaskExecutorTests {

    @MockBean
    private EmbeddingClient embeddingClient;

    @MockBean
    private ChromaGateway chromaGateway;

    @Autowired
    private KnowledgeIndexTaskExecutor executor;

    @Autowired
    private KnowledgeVersionRepository versionRepository;

    @Autowired
    private KnowledgeVersionDocumentRepository documentRepository;

    @Autowired
    private KnowledgeVersionChunkRepository chunkRepository;

    @Autowired
    private KnowledgeIndexTaskRepository taskRepository;

    @Autowired
    private multimodalAgentProperties properties;

    @BeforeEach
    void setUp() {
        properties.getKnowledge().getIndexSync().setEnabled(true);
        properties.getKnowledge().getIndexSync().setMaxAttempts(1);
        properties.getKnowledge().setChunkSize(512);
        properties.getKnowledge().setChunkOverlap(80);
    }

    @Test
    void localBaselineBuildCreatesChunksAndActivatesVersionWithoutExternalCalls() {
        properties.getKnowledge().setRetrievalMode("LOCAL_BASELINE");
        properties.getKnowledge().setUseChroma(false);
        KnowledgeIndexTask task = createTask("Sleep support guidance.");
        KnowledgeIndexTask persisted = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(KnowledgeIndexTaskStatus.PENDING);
        assertThat(persisted.getNextAttemptAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(taskRepository.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                KnowledgeIndexTaskStatus.PENDING, Instant.now(), PageRequest.of(0, 2)))
                .extracting(KnowledgeIndexTask::getId)
                .contains(task.getId());

        executor.pollDueTasks();

        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeIndexTaskStatus.SUCCEEDED);
        assertThat(versionRepository.findById(task.getKnowledgeVersionId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeVersionStatus.ACTIVE);
        assertThat(chunkRepository.findByKnowledgeVersionIdOrderBySourceAscSourceIndexAsc(task.getKnowledgeVersionId()))
                .singleElement()
                .extracting(chunk -> chunk.getContent())
                .isEqualTo("Sleep support guidance.");
        verifyNoInteractions(embeddingClient, chromaGateway);
    }

    @Test
    void chromaFailureMarksVersionFailedAfterTerminalAttempt() {
        properties.getKnowledge().setRetrievalMode("CHROMA_REQUIRED");
        properties.getKnowledge().setUseChroma(true);
        properties.getEmbedding().setDimensions(2);
        when(embeddingClient.modelName()).thenReturn("test-embedding");
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1, 0.2));
        doThrow(new IllegalStateException("chroma unavailable"))
                .when(chromaGateway)
                .mirrorVersionChunk(anyString(), anyString(), any(), any(), anyString(), anyInt(), anyString(), anyList());
        KnowledgeIndexTask task = createTask("Sleep support guidance.");

        executor.pollDueTasks();

        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeIndexTaskStatus.FAILED);
        assertThat(versionRepository.findById(task.getKnowledgeVersionId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeVersionStatus.FAILED);
    }

    private KnowledgeIndexTask createTask(String content) {
        KnowledgeVersion version = new KnowledgeVersion();
        version.setEmbeddingModel("test-embedding");
        version.setEmbeddingDimensions(2);
        version.setChunkSize(512);
        version.setChunkOverlap(80);
        version.setCollectionName("knowledge_test_" + System.nanoTime());
        version.setSourceCount(1);
        version = versionRepository.saveAndFlush(version);

        KnowledgeVersionDocument document = new KnowledgeVersionDocument();
        document.setKnowledgeVersionId(version.getId());
        document.setSource("sleep.md");
        document.setContent(content);
        document.setContentHash("hash");
        documentRepository.saveAndFlush(document);

        KnowledgeIndexTask task = new KnowledgeIndexTask();
        task.setKnowledgeVersionId(version.getId());
        task.setIdempotencyKey("task:" + version.getVersionKey());
        task.setStatus(KnowledgeIndexTaskStatus.PENDING);
        task.setNextAttemptAt(java.time.Instant.now().minusSeconds(1));
        return taskRepository.saveAndFlush(task);
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        multimodalAgentProperties properties() {
            return new multimodalAgentProperties();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
