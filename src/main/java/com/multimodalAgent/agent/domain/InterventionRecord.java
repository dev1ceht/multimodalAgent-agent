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
import java.time.Instant;

/** Append-only factual record of a human care action. */
@Entity
@Table(
        name = "risk_case_interventions",
        indexes = {
                @Index(name = "idx_intervention_case_occurred", columnList = "risk_case_id,occurred_at")
        })
public class InterventionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "risk_case_id", nullable = false)
    private RiskCase riskCase;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private UserAccount createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private InterventionType type;

    @Column(nullable = false, length = 4000)
    private String notes;

    @Column(length = 500)
    private String outcome;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "follow_up_at")
    private Instant followUpAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

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

    public InterventionType getType() {
        return type;
    }

    public void setType(InterventionType type) {
        this.type = type;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public Instant getFollowUpAt() {
        return followUpAt;
    }

    public void setFollowUpAt(Instant followUpAt) {
        this.followUpAt = followUpAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
