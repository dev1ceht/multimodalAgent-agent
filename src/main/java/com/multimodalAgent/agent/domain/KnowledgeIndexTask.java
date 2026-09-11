package com.multimodalAgent.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** 知识版本到外部检索索引 projection 的持久化同步任务。 */
@Entity
@Table(
        name = "knowledge_index_tasks",
        indexes = {
                @Index(name = "idx_knowledge_index_due", columnList = "status,next_attempt_at"),
                @Index(name = "idx_knowledge_index_lease", columnList = "status,lease_until")
        })
public class KnowledgeIndexTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "knowledge_version_id", nullable = false)
    private Long knowledgeVersionId;

    @Column(name = "dispatch_generation", nullable = false)
    private long dispatchGeneration = 1;

    @Column(name = "build_attempt_id", length = 36)
    private String buildAttemptId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KnowledgeIndexTaskStatus status = KnowledgeIndexTaskStatus.PENDING;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 120)
    private String idempotencyKey;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "lease_token", length = 64)
    private String leaseToken;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    public Long getId() {
        return id;
    }

    public Long getKnowledgeVersionId() {
        return knowledgeVersionId;
    }

    public void setKnowledgeVersionId(Long knowledgeVersionId) {
        this.knowledgeVersionId = knowledgeVersionId;
        touch();
    }

    public long getDispatchGeneration() {
        return dispatchGeneration;
    }

    public void setDispatchGeneration(long dispatchGeneration) {
        this.dispatchGeneration = dispatchGeneration;
        touch();
    }

    public void incrementDispatchGeneration() {
        dispatchGeneration++;
        touch();
    }

    public String getBuildAttemptId() {
        return buildAttemptId;
    }

    public void setBuildAttemptId(String buildAttemptId) {
        this.buildAttemptId = buildAttemptId;
        touch();
    }

    public KnowledgeIndexTaskStatus getStatus() {
        return status;
    }

    public void setStatus(KnowledgeIndexTaskStatus status) {
        this.status = status;
        touch();
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public int getAttempts() {
        return attempts;
    }

    public void incrementAttempts() {
        attempts++;
        touch();
    }

    public void resetForManualRetry() {
        dispatchGeneration++;
        status = KnowledgeIndexTaskStatus.PENDING;
        attempts = 0;
        nextAttemptAt = Instant.now();
        leaseUntil = null;
        leaseToken = null;
        buildAttemptId = null;
        lastError = null;
        completedAt = null;
        touch();
    }

    /** Requeues the same business task as a new event generation without resetting total attempts. */
    public void requeueForNextGeneration() {
        dispatchGeneration++;
        status = KnowledgeIndexTaskStatus.PENDING;
        nextAttemptAt = Instant.now();
        leaseUntil = null;
        leaseToken = null;
        completedAt = null;
        touch();
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
        touch();
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(Instant leaseUntil) {
        this.leaseUntil = leaseUntil;
        touch();
    }

    public String getLeaseToken() {
        return leaseToken;
    }

    public void setLeaseToken(String leaseToken) {
        this.leaseToken = leaseToken;
        touch();
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
        touch();
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
        touch();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private void touch() {
        updatedAt = Instant.now();
    }
}
