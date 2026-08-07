package com.multimodalAgent.agent.dto;

import com.multimodalAgent.agent.domain.AuditAction;
import com.multimodalAgent.agent.domain.AuditLog;
import com.multimodalAgent.agent.domain.AuditOutcome;
import com.multimodalAgent.agent.domain.AuditResourceType;
import java.time.Instant;

/** Safe administrator-facing projection of a durable audit record. */
public record AuditLogResponse(
        Long id,
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

    public static AuditLogResponse from(AuditLog auditLog) {
        return new AuditLogResponse(
                auditLog.getId(),
                auditLog.getActorUserId(),
                auditLog.getStudentUserId(),
                auditLog.getActorUsername(),
                auditLog.getAction(),
                auditLog.getResourceType(),
                auditLog.getResourceRef(),
                auditLog.getOutcome(),
                auditLog.getRequestId(),
                auditLog.getIpAddress(),
                auditLog.getUserAgent(),
                auditLog.getDetails(),
                auditLog.getCreatedAt());
    }
}
