package com.multimodalAgent.agent.domain;

import com.multimodalAgent.agent.service.context.ContextJobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Coalesced per-session request for asynchronous context compaction. */
@Entity
@Table(name = "conversation_context_jobs")
public class ConversationContextJob {

    @Id
    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "desired_through_message_id", nullable = false)
    private Long desiredThroughMessageId = 0L;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ContextJobStatus status = ContextJobStatus.IDLE;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "lease_token", length = 36)
    private String leaseToken;

    @Column(name = "claimed_base_version")
    private Long claimedBaseVersion;

    @Column(name = "claimed_target_id")
    private Long claimedTargetId;

    @Column(name = "last_error_code", length = 160)
    private String lastErrorCode;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long value) { sessionId = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public Long getDesiredThroughMessageId() { return desiredThroughMessageId; }
    public void setDesiredThroughMessageId(Long value) { desiredThroughMessageId = value; }
    public ContextJobStatus getStatus() { return status; }
    public void setStatus(ContextJobStatus value) { status = value; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int value) { attempts = value; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant value) { nextAttemptAt = value; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant value) { leaseUntil = value; }
    public String getLeaseToken() { return leaseToken; }
    public void setLeaseToken(String value) { leaseToken = value; }
    public Long getClaimedBaseVersion() { return claimedBaseVersion; }
    public void setClaimedBaseVersion(Long value) { claimedBaseVersion = value; }
    public Long getClaimedTargetId() { return claimedTargetId; }
    public void setClaimedTargetId(Long value) { claimedTargetId = value; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String value) { lastErrorCode = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
