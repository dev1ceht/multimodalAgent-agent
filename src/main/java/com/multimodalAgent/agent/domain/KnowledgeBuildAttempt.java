package com.multimodalAgent.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Isolated Qdrant collection and database projection for one index attempt. */
@Entity
@Table(
        name = "knowledge_build_attempts",
        indexes = @Index(name = "idx_knowledge_build_attempt_task", columnList = "task_id,status"))
public class KnowledgeBuildAttempt {

    @Id
    @Column(name = "build_attempt_id", length = 36, updatable = false)
    private String buildAttemptId = UUID.randomUUID().toString();

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "knowledge_version_id", nullable = false)
    private Long knowledgeVersionId;

    @Column(name = "dispatch_generation", nullable = false)
    private long dispatchGeneration;

    @Column(name = "collection_name", nullable = false, unique = true, length = 120)
    private String collectionName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KnowledgeBuildAttemptStatus status = KnowledgeBuildAttemptStatus.BUILDING;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;

    public String getBuildAttemptId() { return buildAttemptId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long value) { taskId = value; }
    public Long getKnowledgeVersionId() { return knowledgeVersionId; }
    public void setKnowledgeVersionId(Long value) { knowledgeVersionId = value; }
    public long getDispatchGeneration() { return dispatchGeneration; }
    public void setDispatchGeneration(long value) { dispatchGeneration = value; }
    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String value) { collectionName = value; }
    public KnowledgeBuildAttemptStatus getStatus() { return status; }
    public void setStatus(KnowledgeBuildAttemptStatus value) { status = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void mark(KnowledgeBuildAttemptStatus value) {
        status = value;
        completedAt = value == KnowledgeBuildAttemptStatus.BUILDING ? null : Instant.now();
    }
}
