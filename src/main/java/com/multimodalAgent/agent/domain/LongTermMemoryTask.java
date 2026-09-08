package com.multimodalAgent.agent.domain;

import com.multimodalAgent.agent.service.memory.MemoryTaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
        name = "long_term_memory_tasks",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_long_term_memory_task_message",
                columnNames = "source_message_id"))
public class LongTermMemoryTask {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "source_message_id", nullable = false)
    private Long sourceMessageId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long sessionId;

    @Column(nullable = false, length = 64)
    private String sessionPublicId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageRole role;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Column(nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemoryTaskStatus status = MemoryTaskStatus.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private Instant nextAttemptAt = Instant.now();

    private Instant leaseUntil;

    @Column(length = 36)
    private String leaseToken;

    @Column(length = 1000)
    private String lastError;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant completedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(Long value) { sourceMessageId = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long value) { sessionId = value; }
    public String getSessionPublicId() { return sessionPublicId; }
    public void setSessionPublicId(String value) { sessionPublicId = value; }
    public MessageRole getRole() { return role; }
    public void setRole(MessageRole value) { role = value; }
    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant value) { occurredAt = value; }
    public MemoryTaskStatus getStatus() { return status; }
    public void setStatus(MemoryTaskStatus value) { status = value; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int value) { attempts = value; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant value) { nextAttemptAt = value; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant value) { leaseUntil = value; }
    public String getLeaseToken() { return leaseToken; }
    public void setLeaseToken(String value) { leaseToken = value; }
    public String getLastError() { return lastError; }
    public void setLastError(String value) { lastError = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant value) { completedAt = value; }
}
