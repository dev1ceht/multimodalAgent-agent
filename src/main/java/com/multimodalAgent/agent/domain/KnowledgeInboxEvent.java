package com.multimodalAgent.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/** Durable inbox de-duplication and worker hand-off record for a Kafka event. */
@Entity
@Table(
        name = "knowledge_inbox_events",
        indexes = {
                @Index(name = "idx_knowledge_inbox_queued", columnList = "status,lease_until,updated_at"),
                @Index(name = "idx_knowledge_inbox_aggregate", columnList = "aggregate_id")
        })
public class KnowledgeInboxEvent {

    @Id
    @Column(name = "event_id", length = 36, updatable = false)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private KnowledgeEventType eventType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "dispatch_generation", nullable = false)
    private long dispatchGeneration;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KnowledgeInboxStatus status = KnowledgeInboxStatus.QUEUED;

    @Column(name = "lease_token", length = 64)
    private String leaseToken;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public String getEventId() { return eventId; }
    public void setEventId(String value) { eventId = value; }
    public KnowledgeEventType getEventType() { return eventType; }
    public void setEventType(KnowledgeEventType value) { eventType = value; touch(); }
    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String value) { aggregateId = value; touch(); }
    public long getDispatchGeneration() { return dispatchGeneration; }
    public void setDispatchGeneration(long value) { dispatchGeneration = value; touch(); }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String value) { payloadHash = value; touch(); }
    public KnowledgeInboxStatus getStatus() { return status; }
    public void setStatus(KnowledgeInboxStatus value) { status = value; touch(); }
    public String getLeaseToken() { return leaseToken; }
    public void setLeaseToken(String value) { leaseToken = value; touch(); }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant value) { leaseUntil = value; touch(); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void claim(String token, Instant until) {
        status = KnowledgeInboxStatus.RUNNING;
        leaseToken = token;
        leaseUntil = until;
        touch();
    }

    public void markDone(boolean obsolete) {
        status = obsolete ? KnowledgeInboxStatus.OBSOLETE : KnowledgeInboxStatus.DONE;
        leaseToken = null;
        leaseUntil = null;
        touch();
    }

    private void touch() { updatedAt = Instant.now(); }
}
