package com.multimodalAgent.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** Durable handoff from a risk case to a care destination. */
@Entity
@Table(
        name = "risk_case_referrals",
        indexes = {
                @Index(name = "idx_referral_case_created", columnList = "risk_case_id,created_at"),
                @Index(name = "idx_referral_target_status", columnList = "target_user_id,status")
        })
public class Referral {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "risk_case_id", nullable = false)
    private RiskCase riskCase;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private UserAccount createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_user_id")
    private UserAccount targetUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 32)
    private ReferralTargetType targetType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReferralStatus status = ReferralStatus.PENDING;

    @Column(nullable = false, length = 240)
    private String reason;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "completed_at")
    private Instant completedAt;

    public Long getId() {
        return id;
    }

    public RiskCase getRiskCase() {
        return riskCase;
    }

    public void setRiskCase(RiskCase riskCase) {
        this.riskCase = riskCase;
    }

    public UserAccount getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UserAccount createdBy) {
        this.createdBy = createdBy;
    }

    public UserAccount getTargetUser() {
        return targetUser;
    }

    public void setTargetUser(UserAccount targetUser) {
        this.targetUser = targetUser;
    }

    public ReferralTargetType getTargetType() {
        return targetType;
    }

    public void setTargetType(ReferralTargetType targetType) {
        this.targetType = targetType;
    }

    public ReferralStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public void setDueAt(Instant dueAt) {
        this.dueAt = dueAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void transitionTo(ReferralStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("Referral cannot transition from " + status + " to " + target);
        }
        if (target == status) {
            return;
        }
        Instant now = Instant.now();
        status = target;
        updatedAt = now;
        if (target == ReferralStatus.COMPLETED) {
            completedAt = now;
        }
    }
}
