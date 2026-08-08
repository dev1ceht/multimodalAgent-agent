package com.multimodalAgent.agent.dto;

import com.multimodalAgent.agent.domain.ConsentRecord;
import com.multimodalAgent.agent.domain.ConsentStatus;
import com.multimodalAgent.agent.domain.ConsentType;
import java.time.Instant;

public record ConsentResponse(
        Long id,
        ConsentType consentType,
        String version,
        ConsentStatus status,
        Instant grantedAt,
        Instant revokedAt,
        Instant createdAt
) {
    public static ConsentResponse from(ConsentRecord record) {
        return new ConsentResponse(
                record.getId(),
                record.getConsentType(),
                record.getVersion(),
                record.getStatus(),
                record.getGrantedAt(),
                record.getRevokedAt(),
                record.getCreatedAt());
    }
}
