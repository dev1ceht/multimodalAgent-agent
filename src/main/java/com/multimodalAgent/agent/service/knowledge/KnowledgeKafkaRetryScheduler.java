package com.multimodalAgent.agent.service.knowledge;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.KnowledgeIndexTask;
import com.multimodalAgent.agent.domain.KnowledgeIndexTaskStatus;
import com.multimodalAgent.agent.domain.KnowledgeVersionStatus;
import com.multimodalAgent.agent.repository.KnowledgeIndexTaskRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Re-emits only due business retries as a new generation; it never executes work directly. */
@Component
@ConditionalOnProperty(
        prefix = "multimodal-agent.knowledge",
        name = "ingestion-mode",
        havingValue = "kafka-minio")
public class KnowledgeKafkaRetryScheduler {

    private final KnowledgeIndexTaskRepository taskRepository;
    private final KnowledgeVersionRepository versionRepository;
    private final KnowledgeOutboxService outboxService;
    private final KnowledgePublicationLockService publicationLockService;
    private final multimodalAgentProperties properties;
    private final TransactionTemplate transactionTemplate;

    public KnowledgeKafkaRetryScheduler(
            KnowledgeIndexTaskRepository taskRepository,
            KnowledgeVersionRepository versionRepository,
            KnowledgeOutboxService outboxService,
            KnowledgePublicationLockService publicationLockService,
            multimodalAgentProperties properties,
            PlatformTransactionManager transactionManager) {
        this.taskRepository = taskRepository;
        this.versionRepository = versionRepository;
        this.outboxService = outboxService;
        this.publicationLockService = publicationLockService;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${multimodal-agent.knowledge.kafka.poll-interval-ms:1000}")
    public void publishDueRetries() {
        Instant now = Instant.now();
        List<KnowledgeIndexTask> tasks = taskRepository
                .findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        KnowledgeIndexTaskStatus.RETRY_WAIT,
                        now,
                        PageRequest.of(0, Math.max(1, properties.getKnowledge().getKafka().getBatchSize())));
        for (KnowledgeIndexTask candidate : tasks) {
            transactionTemplate.executeWithoutResult(status -> {
                publicationLockService.lock();
                KnowledgeIndexTask task = taskRepository.findByIdForUpdate(candidate.getId()).orElse(null);
                if (task == null || task.getStatus() != KnowledgeIndexTaskStatus.RETRY_WAIT
                        || task.getNextAttemptAt().isAfter(Instant.now())) return;
                var version = versionRepository.findById(task.getKnowledgeVersionId()).orElse(null);
                if (version == null || version.getStatus() == KnowledgeVersionStatus.SUPERSEDED
                        || version.getStatus() == KnowledgeVersionStatus.FAILED
                        || version.getStatus() == KnowledgeVersionStatus.ACTIVE) {
                    task.setStatus(KnowledgeIndexTaskStatus.FAILED);
                    taskRepository.save(task);
                    return;
                }
                task.requeueForNextGeneration();
                taskRepository.save(task);
                outboxService.enqueueIndexRequested(task, "retry:" + task.getId());
            });
        }
    }
}
