package com.multimodalAgent.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
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
import com.multimodalAgent.agent.service.observability.OperationalMetrics;
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
    private ElasticsearchGateway elasticsearchGateway;

    @MockBean
    private OperationalMetrics operationalMetrics;

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
        KnowledgeIndexTask task = createTask("Sleep support guidance.");
        KnowledgeIndexTask persisted = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(KnowledgeIndexTaskStatus.PENDING);
        assertThat(persisted.getNextAttemptAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(taskRepository.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                KnowledgeIndexTaskStatus.PENDING, Instant.now(), PageRequest.of(0, 2)))
                .extracting(KnowledgeIndexTask::getId)
                .contains(task.getId());

        pollUntilProcessed(task.getId());

        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeIndexTaskStatus.SUCCEEDED);
        assertThat(versionRepository.findById(task.getKnowledgeVersionId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeVersionStatus.ACTIVE);
        assertThat(chunkRepository.findByKnowledgeVersionIdOrderBySourceAscSourceIndexAsc(task.getKnowledgeVersionId()))
                .singleElement()
                .extracting(chunk -> chunk.getContent())
                .isEqualTo("Sleep support guidance.");
        verifyNoInteractions(embeddingClient, elasticsearchGateway);
        verify(operationalMetrics).recordIndexTask(eq("succeeded"), anyString(), anyLong());
    }

    @Test
    void elasticsearchBuildIndexesAllChunksBeforeActivatingVersionAlias() {
        properties.getKnowledge().setRetrievalMode("ELASTICSEARCH_REQUIRED");
        properties.getKnowledge().setUseElasticsearch(true);
        properties.getKnowledge().setElasticsearchActiveAlias("mindcare-knowledge-active");
        properties.getEmbedding().setDimensions(2);
        when(embeddingClient.modelName()).thenReturn("test-embedding");
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1, 0.2));
        KnowledgeIndexTask task = createTask("Sleep support guidance.");
        KnowledgeVersion version = versionRepository.findById(task.getKnowledgeVersionId()).orElseThrow();
        when(elasticsearchGateway.refreshAndCount(version.getCollectionName())).thenReturn(1L);

        pollUntilProcessed(task.getId());

        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeIndexTaskStatus.SUCCEEDED);
        assertThat(versionRepository.findById(task.getKnowledgeVersionId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeVersionStatus.ACTIVE);
        verify(elasticsearchGateway).prepareVersionIndex(version.getCollectionName(), 2);
        verify(elasticsearchGateway).indexVersionChunk(
                eq(version.getCollectionName()),
                anyString(),
                anyLong(),
                eq(version.getVersionKey()),
                eq("sleep.md"),
                eq(0),
                eq("Sleep support guidance."),
                eq(List.of(0.1, 0.2)));
        verify(elasticsearchGateway).refreshAndCount(version.getCollectionName());
        verify(elasticsearchGateway).activateAlias(
                version.getCollectionName(),
                "mindcare-knowledge-active");
    }

    @Test
    void elasticsearchCountMismatchFailsVersionWithoutSwitchingAlias() {
        properties.getKnowledge().setRetrievalMode("ELASTICSEARCH_REQUIRED");
        properties.getKnowledge().setUseElasticsearch(true);
        properties.getEmbedding().setDimensions(2);
        when(embeddingClient.modelName()).thenReturn("test-embedding");
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1, 0.2));
        KnowledgeIndexTask task = createTask("Sleep support guidance.");
        KnowledgeVersion version = versionRepository.findById(task.getKnowledgeVersionId()).orElseThrow();
        when(elasticsearchGateway.refreshAndCount(version.getCollectionName())).thenReturn(0L);

        pollUntilProcessed(task.getId());

        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeIndexTaskStatus.FAILED);
        assertThat(versionRepository.findById(task.getKnowledgeVersionId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeVersionStatus.FAILED);
        verify(elasticsearchGateway, org.mockito.Mockito.never())
                .activateAlias(anyString(), anyString());
    }

    @Test
    void retryAfterActivationCompletesTaskWithoutRebuildingActiveIndex() {
        properties.getKnowledge().setRetrievalMode("ELASTICSEARCH_REQUIRED");
        properties.getKnowledge().setUseElasticsearch(true);
        KnowledgeIndexTask task = createTask("Sleep support guidance.");
        KnowledgeVersion version = versionRepository.findById(task.getKnowledgeVersionId()).orElseThrow();
        version.markReady(1);
        version.markActive();
        versionRepository.saveAndFlush(version);

        pollUntilProcessed(task.getId());

        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeIndexTaskStatus.SUCCEEDED);
        verifyNoInteractions(embeddingClient, elasticsearchGateway);
    }

    @Test
    void expiredLeaseCannotActivateTheKnowledgeVersion() {
        properties.getKnowledge().setRetrievalMode("ELASTICSEARCH_REQUIRED");
        properties.getKnowledge().setUseElasticsearch(true);
        properties.getEmbedding().setDimensions(2);
        when(embeddingClient.modelName()).thenReturn("test-embedding");
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1, 0.2));
        KnowledgeIndexTask task = createTask("Sleep support guidance.");
        KnowledgeVersion version = versionRepository.findById(task.getKnowledgeVersionId()).orElseThrow();
        when(elasticsearchGateway.refreshAndCount(version.getCollectionName())).thenReturn(1L);
        doAnswer(invocation -> {
            KnowledgeIndexTask persisted = taskRepository.findById(task.getId()).orElseThrow();
            persisted.setLeaseUntil(Instant.now().minusSeconds(1));
            taskRepository.saveAndFlush(persisted);
            return null;
        }).when(elasticsearchGateway)
                .indexVersionChunk(anyString(), anyString(), any(), anyString(), anyString(), anyInt(), anyString(), anyList());

        pollUntilProcessed(task.getId());

        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeIndexTaskStatus.FAILED);
        assertThat(versionRepository.findById(task.getKnowledgeVersionId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeVersionStatus.FAILED);
        verify(operationalMetrics).recordIndexTask(eq("failed"), anyString(), anyLong());
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

    private void pollUntilProcessed(Long taskId) {
        for (int attempt = 0; attempt < 20; attempt++) {
            executor.pollDueTasks();
            KnowledgeIndexTaskStatus status = taskRepository.findById(taskId)
                    .orElseThrow()
                    .getStatus();
            if (status != KnowledgeIndexTaskStatus.PENDING) {
                return;
            }
            // The application enables scheduling globally. A startup poll can briefly own the
            // executor's drain guard while this test invokes it directly.
            try {
                Thread.sleep(5);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for index task polling.", exception);
            }
        }
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
