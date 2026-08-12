package com.multimodalAgent.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.KnowledgeIndexTaskStatus;
import com.multimodalAgent.agent.domain.KnowledgeVersionStatus;
import com.multimodalAgent.agent.repository.KnowledgeDocumentRepository;
import com.multimodalAgent.agent.repository.KnowledgeIndexTaskRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionChunkRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionDocumentRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionRepository;
import java.util.List;
import org.springframework.web.server.ResponseStatusException;
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

    @Test
    void exposesDocumentSummariesAndFullDocumentDetails() {
        knowledgeService.ingestBatch(List.of(
                new KnowledgeDocumentInput("sleep.md", "Sleep support with a consistent routine."),
                new KnowledgeDocumentInput("anxiety.md", "Grounding support.")));

        KnowledgeDocumentPage page = knowledgeService.listDocuments(0, 20);
        List<KnowledgeDocumentSummary> documents = page.documents();

        assertThat(documents).extracting(KnowledgeDocumentSummary::source)
                .containsExactly("anxiety.md", "sleep.md");
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(documents).allSatisfy(document -> {
            assertThat(document.id()).isNotNull();
            assertThat(document.characterCount()).isPositive();
            assertThat(document.preview()).isNotBlank();
        });
        KnowledgeDocumentDetails details = knowledgeService.getDocument(documents.get(0).id());
        assertThat(details.source()).isEqualTo("anxiety.md");
        assertThat(details.content()).isEqualTo("Grounding support.");
    }

    @Test
    void updatesCanonicalDocumentAndPublishesANewSnapshot() {
        knowledgeService.ingest("sleep.md", "Old guidance");
        Long documentId = documentRepository.findBySource("sleep.md").orElseThrow().getId();

        KnowledgeDocumentDetails updated = knowledgeService.updateDocument(
                documentId,
                "sleep-guide.md",
                "Updated sleep guidance",
                documentRepository.findById(documentId).orElseThrow().getVersion());

        assertThat(updated.source()).isEqualTo("sleep-guide.md");
        assertThat(documentRepository.findBySource("sleep.md")).isEmpty();
        assertThat(documentRepository.findBySource("sleep-guide.md")).isPresent();
        assertThat(versionRepository.count()).isEqualTo(2);
        assertThat(versionDocumentRepository.findAll())
                .extracting(document -> document.getSource())
                .contains("sleep-guide.md");
    }

    @Test
    void createDocumentRejectsAnExistingSourceInsteadOfSilentlyReplacingIt() {
        knowledgeService.createDocument("sleep.md", "Original guidance");

        assertThatThrownBy(() -> knowledgeService.createDocument("sleep.md", "Replacement guidance"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already exists");
        assertThat(documentRepository.findBySource("sleep.md").orElseThrow().getContent())
                .isEqualTo("Original guidance");
        assertThat(versionRepository.count()).isEqualTo(1);
    }

    @Test
    void rejectsRenamingADocumentToAnExistingSource() {
        knowledgeService.ingestBatch(List.of(
                new KnowledgeDocumentInput("sleep.md", "Sleep support"),
                new KnowledgeDocumentInput("anxiety.md", "Grounding support")));
        Long documentId = documentRepository.findBySource("sleep.md").orElseThrow().getId();

        assertThatThrownBy(() -> knowledgeService.updateDocument(
                documentId,
                "anxiety.md",
                "Replacement content",
                documentRepository.findById(documentId).orElseThrow().getVersion()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void rejectsStaleDocumentUpdates() {
        knowledgeService.ingest("sleep.md", "Sleep support");
        var document = documentRepository.findBySource("sleep.md").orElseThrow();

        assertThatThrownBy(() -> knowledgeService.updateDocument(
                document.getId(),
                "sleep.md",
                "Stale replacement",
                document.getVersion() + 1))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("updated by another administrator");
    }

    @Test
    void deletingLastDocumentPublishesAnEmptySnapshot() {
        knowledgeService.ingest("sleep.md", "Sleep support");
        Long documentId = documentRepository.findBySource("sleep.md").orElseThrow().getId();

        String deletedSource = knowledgeService.deleteDocument(
                documentId,
                documentRepository.findById(documentId).orElseThrow().getVersion());

        assertThat(deletedSource).isEqualTo("sleep.md");
        assertThat(documentRepository.count()).isZero();
        assertThat(versionRepository.count()).isEqualTo(2);
        assertThat(versionRepository.findTopByOrderByCreatedAtDescIdDesc().orElseThrow().getSourceCount())
                .isZero();
        assertThat(taskRepository.count()).isEqualTo(2);
    }

    @Test
    void retriesOnlyTheLatestFailedVersionAndResetsItsTask() {
        knowledgeService.ingest("sleep.md", "Sleep support");
        var version = versionRepository.findTopByOrderByCreatedAtDescIdDesc().orElseThrow();
        version.markFailed("embedding unavailable");
        versionRepository.save(version);
        var task = taskRepository.findTopByKnowledgeVersionIdOrderByCreatedAtDesc(version.getId())
                .orElseThrow();
        task.incrementAttempts();
        task.setStatus(KnowledgeIndexTaskStatus.FAILED);
        task.setLastError("embedding unavailable");
        taskRepository.save(task);

        KnowledgePublicationStatus status = knowledgeService.retryVersion(version.getVersionKey());

        var retriedTask = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(status.latestVersionStatus()).isEqualTo(KnowledgeVersionStatus.BUILDING);
        assertThat(status.latestTaskStatus()).isEqualTo(KnowledgeIndexTaskStatus.PENDING);
        assertThat(retriedTask.getAttempts()).isZero();
        assertThat(retriedTask.getLastError()).isNull();
        assertThat(retriedTask.getLeaseToken()).isNull();
        assertThat(retriedTask.getLeaseUntil()).isNull();
    }

    @Test
    void exposesVersionHistoryWithTaskStateAndRetryability() {
        knowledgeService.ingest("sleep.md", "Sleep support");
        var version = versionRepository.findTopByOrderByCreatedAtDescIdDesc().orElseThrow();
        version.markFailed("embedding unavailable");
        versionRepository.save(version);
        var task = taskRepository.findTopByKnowledgeVersionIdOrderByCreatedAtDesc(version.getId())
                .orElseThrow();
        task.setStatus(KnowledgeIndexTaskStatus.FAILED);
        task.setLastError("embedding unavailable");
        taskRepository.save(task);

        List<KnowledgeVersionSummary> versions = knowledgeService.listVersions();

        assertThat(versions).singleElement().satisfies(summary -> {
            assertThat(summary.versionKey()).isEqualTo(version.getVersionKey());
            assertThat(summary.status()).isEqualTo(KnowledgeVersionStatus.FAILED);
            assertThat(summary.taskStatus()).isEqualTo(KnowledgeIndexTaskStatus.FAILED);
            assertThat(summary.lastError()).isEqualTo("embedding unavailable");
            assertThat(summary.latest()).isTrue();
            assertThat(summary.retryable()).isTrue();
        });
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        multimodalAgentProperties properties() {
            return new multimodalAgentProperties();
        }

        @Bean
        KnowledgeChunker knowledgeChunker() {
            return new KnowledgeChunker();
        }
    }
}
