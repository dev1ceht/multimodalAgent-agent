package com.multimodalAgent.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.KnowledgeIndexTaskStatus;
import com.multimodalAgent.agent.domain.KnowledgeVersionStatus;
import com.multimodalAgent.agent.repository.KnowledgeDocumentRepository;
import com.multimodalAgent.agent.repository.KnowledgeIndexTaskRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionChunkRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionDocumentRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import({KnowledgeService.class, KnowledgeServiceTests.TestConfig.class})
class KnowledgeServiceTests {

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private KnowledgeDocumentRepository documentRepository;

    @Autowired
    private KnowledgeVersionRepository versionRepository;

    @Autowired
    private KnowledgeVersionDocumentRepository versionDocumentRepository;

    @Autowired
    private KnowledgeVersionChunkRepository versionChunkRepository;

    @Autowired
    private KnowledgeIndexTaskRepository taskRepository;

    @Test
    void publishesAnImmutableSnapshotAndDurableIndexTaskWithoutWritingLegacyChunks() {
        int chunkCount = knowledgeService.ingest("sleep.md", "# Sleep\n\nKeep a consistent routine.");

        assertThat(chunkCount).isGreaterThan(0);
        assertThat(documentRepository.count()).isEqualTo(1);
        assertThat(versionRepository.count()).isEqualTo(1);
        assertThat(versionDocumentRepository.count()).isEqualTo(1);
        assertThat(versionChunkRepository.count()).isZero();
        assertThat(taskRepository.findAll())
                .extracting(task -> task.getStatus())
                .containsExactly(KnowledgeIndexTaskStatus.PENDING);
        assertThat(versionRepository.findAll())
                .extracting(version -> version.getStatus())
                .containsExactly(KnowledgeVersionStatus.BUILDING);
    }

    @Test
    void identicalContentIsIdempotentAndDoesNotCreateAnotherVersion() {
        knowledgeService.ingest("sleep.md", "Sleep support");
        knowledgeService.ingest("sleep.md", "Sleep support");

        assertThat(documentRepository.count()).isEqualTo(1);
        assertThat(versionRepository.count()).isEqualTo(1);
        assertThat(taskRepository.count()).isEqualTo(1);
    }

    @Test
    void eachVersionContainsAllCurrentDocuments() {
        knowledgeService.ingestBatch(List.of(
                new KnowledgeDocumentInput("sleep.md", "Sleep support"),
                new KnowledgeDocumentInput("anxiety.md", "Grounding support")));

        assertThat(versionRepository.findAll()).singleElement()
                .extracting(version -> version.getSourceCount())
                .isEqualTo(2);
        assertThat(versionDocumentRepository.findAll())
                .extracting(document -> document.getSource())
                .containsExactlyInAnyOrder("anxiety.md", "sleep.md");
    }

    @Test
    void publicationStatusExposesPendingLatestVersionUntilIndexingCompletes() {
        knowledgeService.ingest("sleep.md", "Sleep support");

        KnowledgePublicationStatus status = knowledgeService.publicationStatus();

        assertThat(status.activeVersionKey()).isNull();
        assertThat(status.activeVersionStatus()).isNull();
        assertThat(status.latestVersionKey()).isNotBlank();
        assertThat(status.latestVersionStatus()).isEqualTo(KnowledgeVersionStatus.BUILDING);
        assertThat(status.latestTaskStatus()).isEqualTo(KnowledgeIndexTaskStatus.PENDING);
        assertThat(status.latestTaskAttempts()).isZero();
        assertThat(status.latestSourceCount()).isEqualTo(1);
        assertThat(status.latestChunkCount()).isZero();
        assertThat(status.retrievalReady()).isFalse();
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        multimodalAgentProperties properties() {
            return new multimodalAgentProperties();
        }
    }
}
