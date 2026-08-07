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
import java.time.Instant;
import java.util.Objects;

/** Durable, privacy-preserving record of a sensitive access or management action. */
@Entity
@Table(
        name = "audit_logs",
        indexes = {
                @Index(name = "idx_audit_log_actor_created", columnList = "actor_user_id,created_at"),
                @Index(name = "idx_audit_log_action_created", columnList = "action,created_at"),
                @Index(name = "idx_audit_log_request_id", columnList = "request_id")
        })
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "student_user_id")
    private Long studentUserId;

    @Column(name = "actor_username", nullable = false, length = 80)
    private String actorUsername;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 32)
    private AuditResourceType resourceType;

    @Column(name = "resource_ref", nullable = false, length = 64)
    private String resourceRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuditOutcome outcome;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    @Column(length = 240)
    private String details;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLog() {
    }

    public static AuditLog recorded(
            Long actorUserId,
            Long studentUserId,
            String actorUsername,
            AuditAction action,
            AuditResourceType resourceType,
            String resourceRef,
            AuditOutcome outcome,
            String requestId,
            String ipAddress,
            String userAgent,
            String details,
            Instant createdAt
    ) {
        AuditLog auditLog = new AuditLog();
        auditLog.actorUserId = actorUserId;
        auditLog.studentUserId = studentUserId;
        auditLog.actorUsername = requireText(actorUsername, "actorUsername");
        auditLog.action = Objects.requireNonNull(action, "action");
        auditLog.resourceType = Objects.requireNonNull(resourceType, "resourceType");
        auditLog.resourceRef = requireText(resourceRef, "resourceRef");
        auditLog.outcome = Objects.requireNonNull(outcome, "outcome");
        auditLog.requestId = requireText(requestId, "requestId");
        auditLog.ipAddress = ipAddress;
        auditLog.userAgent = userAgent;
        auditLog.details = details;
        auditLog.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        return auditLog;
    }

    public Long getId() {
        return id;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public Long getStudentUserId() {
        return studentUserId;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public AuditAction getAction() {
        return action;
    }

    public AuditResourceType getResourceType() {
        return resourceType;
    }

    public String getResourceRef() {
        return resourceRef;
    }

    public AuditOutcome getOutcome() {
        return outcome;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getDetails() {
        return details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
