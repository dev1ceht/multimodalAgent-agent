package com.multimodalAgent.agent.service.knowledge;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.KnowledgeEventType;
import com.multimodalAgent.agent.domain.KnowledgeOutboxEvent;
import com.multimodalAgent.agent.domain.KnowledgeOutboxStatus;
import com.multimodalAgent.agent.repository.KnowledgeOutboxEventRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.multimodalAgent.agent.service.observability.OperationalMetrics;

/** At-least-once outbox publisher with a short DB lease around claim and acknowledgement only. */
@Component
@ConditionalOnProperty(
        prefix = "multimodal-agent.knowledge",
        name = "ingestion-mode",
        havingValue = "kafka-minio")
public class KnowledgeOutboxPublisher {

    private final KnowledgeOutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final multimodalAgentProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean draining = new AtomicBoolean();
    private final OperationalMetrics operationalMetrics;

    public KnowledgeOutboxPublisher(
            KnowledgeOutboxEventRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            multimodalAgentProperties properties,
            PlatformTransactionManager transactionManager,
            OperationalMetrics operationalMetrics) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.operationalMetrics = operationalMetrics;
    }

    @Scheduled(fixedDelayString = "${multimodal-agent.knowledge.kafka.poll-interval-ms:1000}")
    public void publishDueEvents() {
        if (!draining.compareAndSet(false, true)) return;
        try {
            Instant now = Instant.now();
            int batchSize = Math.max(1, properties.getKnowledge().getKafka().getBatchSize());
            List<KnowledgeOutboxEvent> candidates = new ArrayList<>();
            PageRequest page = PageRequest.of(0, batchSize);
            candidates.addAll(repository.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                    KnowledgeOutboxStatus.PENDING, now, page));
            candidates.addAll(repository.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                    KnowledgeOutboxStatus.RETRY_WAIT, now, page));
            candidates.addAll(repository.findByStatusAndLeaseUntilLessThanEqualOrderByCreatedAtAsc(
                    KnowledgeOutboxStatus.PROCESSING, now, page));
            for (KnowledgeOutboxEvent candidate : candidates) {
                Claim claim = claim(candidate.getId());
                if (claim != null) publish(claim);
            }
        } finally {
            draining.set(false);
        }
    }

    private Claim claim(Long eventId) {
        return transactionTemplate.execute(status -> {
            KnowledgeOutboxEvent event = repository.findByIdForUpdate(eventId).orElse(null);
            if (event == null || event.getStatus() == KnowledgeOutboxStatus.PUBLISHED) return null;
            Instant now = Instant.now();
            boolean expired = event.getStatus() == KnowledgeOutboxStatus.PROCESSING
                    && event.getLeaseUntil() != null && !event.getLeaseUntil().isAfter(now);
            if (event.getStatus() == KnowledgeOutboxStatus.PROCESSING && !expired) return null;
            String token = UUID.randomUUID().toString();
            event.claim(token, now.plusSeconds(Math.max(1,
                    properties.getKnowledge().getKafka().getLeaseSeconds())));
            repository.saveAndFlush(event);
            return new Claim(event.getId(), event.getEventType(), event.getPartitionKey(),
                    event.getPayloadJson(), token);
        });
    }

    private void publish(Claim claim) {
        String topic = switch (claim.type()) {
            case KNOWLEDGE_PARSE_REQUESTED -> properties.getKnowledge().getKafka().getParseTopic();
            case KNOWLEDGE_INDEX_REQUESTED -> properties.getKnowledge().getKafka().getIndexTopic();
            case KNOWLEDGE_DEAD_LETTER -> properties.getKnowledge().getKafka().getDeadLetterTopic();
        };
        try {
            kafkaTemplate.send(topic, claim.partitionKey(), claim.payload())
                    .get(Math.max(1, properties.getKnowledge().getKafka().getLeaseSeconds()), TimeUnit.SECONDS);
            transactionTemplate.executeWithoutResult(status -> {
                KnowledgeOutboxEvent event = repository.findByIdForUpdate(claim.id()).orElse(null);
                if (event != null && claim.leaseToken().equals(event.getLeaseToken())) {
                    event.markPublished();
                    repository.save(event);
                }
            });
            operationalMetrics.recordKnowledgeStage("outbox", "published");
        } catch (Exception exception) {
            transactionTemplate.executeWithoutResult(status -> {
                KnowledgeOutboxEvent event = repository.findByIdForUpdate(claim.id()).orElse(null);
                if (event != null && claim.leaseToken().equals(event.getLeaseToken())) {
                    event.markRetry(Instant.now().plusSeconds(retryDelay(event.getAttempts())));
                    repository.save(event);
                }
            });
            operationalMetrics.recordKnowledgeStage("outbox", "retry");
        }
    }

    private long retryDelay(int attempts) {
        long base = 5;
        return Math.min(3600, base * (1L << Math.min(8, Math.max(0, attempts - 1))));
    }

    private record Claim(Long id, KnowledgeEventType type, String partitionKey, String payload, String leaseToken) {
    }
}
