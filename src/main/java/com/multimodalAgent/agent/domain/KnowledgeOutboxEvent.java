package com.multimodalAgent.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Transactional event waiting to be published to Kafka. */
@Entity
@Table(
        name = "knowledge_outbox_events",
        indexes = {
                @Index(name = "idx_knowledge_outbox_due", columnList = "status,next_attempt_at"),
                @Index(name = "idx_knowledge_outbox_lease", columnList = "status,lease_until")
        },
        uniqueConstraints = @UniqueConstraint(
                name = "uk_knowledge_outbox_aggregate_generation",
                columnNames = {"event_type", "aggregate_id", "dispatch_generation"}))
public class KnowledgeOutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 36, updatable = false)
    private String eventId = UUID.randomUUID().toString();

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private KnowledgeEventType eventType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "partition_key", nullable = false, length = 180)
    private String partitionKey;

    @Column(name = "dispatch_generation", nullable = false)
    private long dispatchGeneration;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion = 1;

    @Lob
    @Column(name = "payload_json", nullable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KnowledgeOutboxStatus status = KnowledgeOutboxStatus.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "lease_token", length = 64)
    private String leaseToken;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public KnowledgeEventType getEventType() { return eventType; }
    public void setEventType(KnowledgeEventType value) { eventType = value; touch(); }
    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String value) { aggregateId = value; touch(); }
    public String getPartitionKey() { return partitionKey; }
    public void setPartitionKey(String value) { partitionKey = value; touch(); }
    public long getDispatchGeneration() { return dispatchGeneration; }
    public void setDispatchGeneration(long value) { dispatchGeneration = value; touch(); }
    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int value) { schemaVersion = value; touch(); }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String value) { payloadJson = value; touch(); }
    public KnowledgeOutboxStatus getStatus() { return status; }
    public void setStatus(KnowledgeOutboxStatus value) { status = value; touch(); }
    public int getAttempts() { return attempts; }
    public void incrementAttempts() { attempts++; touch(); }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant value) { nextAttemptAt = value; touch(); }
    public String getLeaseToken() { return leaseToken; }
    public void setLeaseToken(String value) { leaseToken = value; touch(); }
    public Instant getLeaseUntil() { return leaseUntil; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant value) { publishedAt = value; touch(); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    public void claim(String token, Instant until) {
        status = KnowledgeOutboxStatus.PROCESSING;
        leaseToken = token;
        leaseUntil = until;
        incrementAttempts();
    }

    public void markPublished() {
        status = KnowledgeOutboxStatus.PUBLISHED;
        publishedAt = Instant.now();
        leaseToken = null;
        leaseUntil = null;
        touch();
    }

    public void markRetry(Instant next) {
        status = KnowledgeOutboxStatus.RETRY_WAIT;
        nextAttemptAt = next;
        leaseToken = null;
        leaseUntil = null;
        touch();
    }

    private void touch() { updatedAt = Instant.now(); }
}
