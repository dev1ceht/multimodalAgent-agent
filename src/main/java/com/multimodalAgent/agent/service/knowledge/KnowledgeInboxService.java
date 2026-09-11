package com.multimodalAgent.agent.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.domain.KnowledgeEventType;
import com.multimodalAgent.agent.domain.KnowledgeInboxEvent;
import com.multimodalAgent.agent.repository.KnowledgeInboxEventRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable Kafka hand-off. The listener commits an offset only after this transaction succeeds. */
@Service
public class KnowledgeInboxService {

    private final KnowledgeInboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public KnowledgeInboxService(KnowledgeInboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Acceptance accept(String rawPayload, KnowledgeEventType expectedType) {
        if (rawPayload == null || rawPayload.isBlank()) {
            throw new KnowledgeParseException("INVALID_EVENT", "Kafka knowledge event is empty", false);
        }
        KnowledgeEvent event;
        try {
            event = objectMapper.readValue(rawPayload, KnowledgeEvent.class);
        } catch (Exception exception) {
            throw new KnowledgeParseException("INVALID_EVENT", "Kafka knowledge event is invalid", false, exception);
        }
        if (event.eventId() == null || event.aggregateId() == null
                || event.eventType() == null || event.dispatchGeneration() < 1
                || event.schemaVersion() != 1) {
            throw new KnowledgeParseException("INVALID_EVENT", "Kafka knowledge event envelope is incomplete", false);
        }
        KnowledgeEventType actual;
        try {
            actual = KnowledgeEventType.valueOf(event.eventType());
        } catch (IllegalArgumentException exception) {
            throw new KnowledgeParseException("UNSUPPORTED_EVENT", "Unknown knowledge event type", false, exception);
        }
        if (actual != expectedType) {
            throw new KnowledgeParseException("UNEXPECTED_EVENT", "Knowledge event type does not match topic", false);
        }
        String payloadHash = sha256(rawPayload);
        KnowledgeInboxEvent existing = repository.findById(event.eventId()).orElse(null);
        if (existing != null) {
            if (!payloadHash.equals(existing.getPayloadHash())) {
                throw new KnowledgeParseException("EVENT_ID_REUSED", "Kafka event id has a different payload", false);
            }
            return new Acceptance(existing, true);
        }
        KnowledgeInboxEvent inbox = new KnowledgeInboxEvent();
        inbox.setEventId(event.eventId());
        inbox.setEventType(actual);
        inbox.setAggregateId(event.aggregateId());
        inbox.setDispatchGeneration(event.dispatchGeneration());
        inbox.setPayloadHash(payloadHash);
        return new Acceptance(repository.saveAndFlush(inbox), false);
    }

    public record Acceptance(KnowledgeInboxEvent inbox, boolean duplicate) {
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot hash knowledge event", exception);
        }
    }
}
