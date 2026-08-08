package com.multimodalAgent.agent.dto;

import com.multimodalAgent.agent.domain.InterventionRecord;
import com.multimodalAgent.agent.domain.InterventionType;
import java.time.Instant;

public record InterventionResponse(
        Long id,
        Long caseId,
        Long authorUserId,
        String authorUsername,
        InterventionType type,
        String notes,
        String outcome,
        Instant occurredAt,
        Instant followUpAt,
        Instant createdAt
) {

    public static InterventionResponse from(InterventionRecord record) {
        return new InterventionResponse(
                record.getId(),
                record.getRiskCase().getId(),
                record.getCreatedBy().getId(),
                record.getCreatedBy().getUsername(),
                record.getType(),
                record.getNotes(),
                record.getOutcome(),
                record.getOccurredAt(),
                record.getFollowUpAt(),
                record.getCreatedAt());
    }
}
