package com.multimodalAgent.agent.service.knowledge;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.KnowledgeBuildAttempt;
import com.multimodalAgent.agent.domain.KnowledgeBuildAttemptStatus;
import com.multimodalAgent.agent.domain.KnowledgeIndexTask;
import com.multimodalAgent.agent.domain.KnowledgeIndexTaskStatus;
import com.multimodalAgent.agent.domain.KnowledgeVersion;
import com.multimodalAgent.agent.domain.KnowledgeVersionStatus;
import com.multimodalAgent.agent.repository.KnowledgeBuildAttemptRepository;
import com.multimodalAgent.agent.repository.KnowledgeIndexTaskRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionChunkRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionSectionRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Reclaims stale isolated build resources without touching the active attempt. */
@Component
@ConditionalOnProperty(
        prefix = "multimodal-agent.knowledge",
        name = "ingestion-mode",
        havingValue = "kafka-minio")
public class KnowledgeBuildAttemptCleanupService {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeBuildAttemptCleanupService.class);

    private final KnowledgeBuildAttemptRepository buildAttemptRepository;
    private final KnowledgeIndexTaskRepository taskRepository;
    private final KnowledgeVersionRepository versionRepository;
    private final KnowledgeVersionChunkRepository chunkRepository;
    private final KnowledgeVersionSectionRepository sectionRepository;
    private final QdrantGateway qdrantGateway;
    private final multimodalAgentProperties properties;
    private final TransactionTemplate transactionTemplate;

    public KnowledgeBuildAttemptCleanupService(
            KnowledgeBuildAttemptRepository buildAttemptRepository,
            KnowledgeIndexTaskRepository taskRepository,
            KnowledgeVersionRepository versionRepository,
            KnowledgeVersionChunkRepository chunkRepository,
            KnowledgeVersionSectionRepository sectionRepository,
            QdrantGateway qdrantGateway,
            multimodalAgentProperties properties,
            PlatformTransactionManager transactionManager) {
        this.buildAttemptRepository = buildAttemptRepository;
        this.taskRepository = taskRepository;
        this.versionRepository = versionRepository;
        this.chunkRepository = chunkRepository;
        this.sectionRepository = sectionRepository;
        this.qdrantGateway = qdrantGateway;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${multimodal-agent.knowledge.kafka.build-attempt-cleanup-interval-ms:3600000}")
    public void cleanupExpiredAttempts() {
        Instant cutoff = Instant.now().minusSeconds(Math.max(1,
                properties.getKnowledge().getKafka().getBuildAttemptRetentionSeconds()));
        int batchSize = Math.max(1, properties.getKnowledge().getKafka().getBuildAttemptCleanupBatchSize());
        List<KnowledgeBuildAttempt> candidates = buildAttemptRepository
                .findByStatusInAndCreatedAtLessThanEqualOrderByCreatedAtAsc(
                        List.of(
                                KnowledgeBuildAttemptStatus.BUILDING,
                                KnowledgeBuildAttemptStatus.FAILED,
                                KnowledgeBuildAttemptStatus.ABANDONED),
                        cutoff,
                        PageRequest.of(0, batchSize));
        for (KnowledgeBuildAttempt candidate : candidates) {
            cleanup(candidate.getBuildAttemptId(), cutoff);
        }
    }

    private void cleanup(String buildAttemptId, Instant cutoff) {
        CleanupTarget target = transactionTemplate.execute(status -> prepare(buildAttemptId, cutoff));
        if (target == null) return;
        try {
            if (qdrantCleanupEnabled()) {
                qdrantGateway.deleteVersionIndex(target.collectionName());
            }
        } catch (RuntimeException exception) {
            logger.warn("Could not clean Qdrant collection for knowledge build attempt {}",
                    buildAttemptId, exception);
            return;
        }
        transactionTemplate.executeWithoutResult(status -> finalizeCleanup(target, cutoff));
    }

    private CleanupTarget prepare(String buildAttemptId, Instant cutoff) {
        KnowledgeBuildAttempt attempt = buildAttemptRepository.findByIdForUpdate(buildAttemptId).orElse(null);
        if (attempt == null || attempt.getCreatedAt() == null
                || attempt.getCreatedAt().isAfter(cutoff)
                || attempt.getStatus() == KnowledgeBuildAttemptStatus.CLEANED
                || hasLiveReference(attempt)) {
            return null;
        }
        if (attempt.getStatus() == KnowledgeBuildAttemptStatus.BUILDING) {
            attempt.mark(KnowledgeBuildAttemptStatus.ABANDONED);
            buildAttemptRepository.saveAndFlush(attempt);
        }
        return new CleanupTarget(
                attempt.getBuildAttemptId(),
                attempt.getKnowledgeVersionId(),
                attempt.getCollectionName());
    }

    private void finalizeCleanup(CleanupTarget target, Instant cutoff) {
        KnowledgeBuildAttempt attempt = buildAttemptRepository.findByIdForUpdate(target.buildAttemptId()).orElse(null);
        if (attempt == null || attempt.getCreatedAt() == null
                || attempt.getCreatedAt().isAfter(cutoff)
                || (attempt.getStatus() != KnowledgeBuildAttemptStatus.FAILED
                && attempt.getStatus() != KnowledgeBuildAttemptStatus.ABANDONED)
                || hasLiveReference(attempt)) {
            return;
        }
        chunkRepository.deleteByKnowledgeVersionIdAndBuildAttemptId(
                target.knowledgeVersionId(), target.buildAttemptId());
        sectionRepository.deleteByKnowledgeVersionIdAndBuildAttemptId(
                target.knowledgeVersionId(), target.buildAttemptId());
        attempt.mark(KnowledgeBuildAttemptStatus.CLEANED);
        buildAttemptRepository.save(attempt);
    }

    private boolean hasLiveReference(KnowledgeBuildAttempt attempt) {
        KnowledgeVersion version = versionRepository.findById(attempt.getKnowledgeVersionId()).orElse(null);
        if (version != null && (attempt.getBuildAttemptId().equals(version.getActiveBuildAttemptId())
                || attempt.getCollectionName().equals(version.getCollectionName())
                && version.getStatus() == KnowledgeVersionStatus.ACTIVE)) {
            return true;
        }
        KnowledgeIndexTask task = taskRepository.findById(attempt.getTaskId()).orElse(null);
        return task != null
                && attempt.getBuildAttemptId().equals(task.getBuildAttemptId())
                && task.getStatus() != KnowledgeIndexTaskStatus.SUCCEEDED
                && task.getStatus() != KnowledgeIndexTaskStatus.FAILED;
    }

    private boolean qdrantCleanupEnabled() {
        String baseUrl = properties.getKnowledge().getQdrantBaseUrl();
        return properties.getKnowledge().isUseQdrant() && baseUrl != null && !baseUrl.isBlank();
    }

    private record CleanupTarget(String buildAttemptId, Long knowledgeVersionId, String collectionName) {
    }
}
