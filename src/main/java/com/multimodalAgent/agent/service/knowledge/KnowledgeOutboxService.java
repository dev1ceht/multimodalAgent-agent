package com.multimodalAgent.agent.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.domain.KnowledgeEventType;
import com.multimodalAgent.agent.domain.KnowledgeIndexTask;
import com.multimodalAgent.agent.domain.KnowledgeOutboxEvent;
import com.multimodalAgent.agent.domain.KnowledgeUpload;
import com.multimodalAgent.agent.repository.KnowledgeOutboxEventRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes durable events in the caller's business transaction; Kafka is deliberately absent here. */
@Service
public class KnowledgeOutboxService {

    private final KnowledgeOutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public KnowledgeOutboxService(KnowledgeOutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public KnowledgeOutboxEvent enqueueParseRequested(KnowledgeUpload upload, String correlationId) {
        return enqueue(
                KnowledgeEventType.KNOWLEDGE_PARSE_REQUESTED,
                upload.getId(),
                upload.getSource(),
                upload.getDispatchGeneration(),
                correlationId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public KnowledgeOutboxEvent enqueueIndexRequested(KnowledgeIndexTask task, String correlationId) {
        return enqueue(
                KnowledgeEventType.KNOWLEDGE_INDEX_REQUESTED,
                String.valueOf(task.getId()),
                "default",
                task.getDispatchGeneration(),
                correlationId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public KnowledgeOutboxEvent enqueueDeadLetter(
            String aggregateId,
            long dispatchGeneration,
            String correlationId) {
        return enqueue(
                KnowledgeEventType.KNOWLEDGE_DEAD_LETTER,
                aggregateId,
                "default",
                dispatchGeneration,
                correlationId);
    }

    private KnowledgeOutboxEvent enqueue(
            KnowledgeEventType eventType,
            String aggregateId,
            String partitionKey,
            long generation,
            String correlationId) {
        KnowledgeOutboxEvent existing = repository
                .findByEventTypeAndAggregateIdAndDispatchGeneration(eventType, aggregateId, generation)
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        KnowledgeEvent event = new KnowledgeEvent(
                UUID.randomUUID().toString(),
                1,
                eventType.name(),
                aggregateId,
                generation,
                Instant.now(),
                correlationId == null || correlationId.isBlank() ? "system" : correlationId);
        KnowledgeOutboxEvent outbox = new KnowledgeOutboxEvent();
        outbox.setEventType(eventType);
        outbox.setAggregateId(aggregateId);
        outbox.setPartitionKey(partitionKey);
        outbox.setDispatchGeneration(generation);
        outbox.setSchemaVersion(event.schemaVersion());
        try {
            outbox.setPayloadJson(objectMapper.writeValueAsString(event));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot serialize knowledge outbox event", exception);
        }
        return repository.save(outbox);
    }
}
