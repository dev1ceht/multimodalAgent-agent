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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** Durable human-follow-up case opened from a high-risk student assessment. */
@Entity
@Table(
        name = "risk_cases",
        indexes = {
                @Index(name = "idx_risk_case_student_updated", columnList = "student_user_id,updated_at"),
                @Index(name = "idx_risk_case_status_updated", columnList = "status,updated_at"),
                @Index(name = "idx_risk_case_sla", columnList = "status,sla_due_at")
        })
public class RiskCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trigger_report_id", nullable = false, unique = true)
    private PsychologicalReport triggerReport;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_user_id", nullable = false)
    private UserAccount studentUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 20)
    private RiskLevel riskLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RiskCaseSource source = RiskCaseSource.AUTOMATED_ASSESSMENT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RiskCaseStatus status = RiskCaseStatus.OPEN;

    @Column(name = "opening_reason", nullable = false, length = 240)
    private String openingReason;

    @Column(nullable = false, updatable = false)
    private Instant openedAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "sla_due_at")
    private Instant slaDueAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    public Long getId() {
        return id;
    }

    public PsychologicalReport getTriggerReport() {
        return triggerReport;
    }

    public void setTriggerReport(PsychologicalReport triggerReport) {
        this.triggerReport = triggerReport;
    }

    public UserAccount getStudentUser() {
        return studentUser;
    }

    public void setStudentUser(UserAccount studentUser) {
        this.studentUser = studentUser;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public RiskCaseSource getSource() {
        return source;
    }

    public void setSource(RiskCaseSource source) {
        this.source = source;
    }

    public RiskCaseStatus getStatus() {
        return status;
    }

    public String getOpeningReason() {
        return openingReason;
    }

    public void setOpeningReason(String openingReason) {
        this.openingReason = openingReason;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getSlaDueAt() {
        return slaDueAt;
    }

    public void setSlaDueAt(Instant slaDueAt) {
        this.slaDueAt = slaDueAt;
    }

    public long getVersion() {
        return version;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void transitionTo(RiskCaseStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("Risk case cannot transition from " + status + " to " + target);
        }
        if (target == status) {
            return;
        }
        Instant now = Instant.now();
        status = target;
        updatedAt = now;
        if (target == RiskCaseStatus.ACKNOWLEDGED && acknowledgedAt == null) {
            acknowledgedAt = now;
        }
        if (target == RiskCaseStatus.RESOLVED) {
            resolvedAt = now;
        }
        if (target == RiskCaseStatus.CLOSED) {
            closedAt = now;
        }
    }
}
