package com.multimodalAgent.agent.dto;

import com.multimodalAgent.agent.domain.Referral;
import com.multimodalAgent.agent.domain.ReferralStatus;
import com.multimodalAgent.agent.domain.ReferralTargetType;
import java.time.Instant;

public record ReferralResponse(
        Long id,
        Long caseId,
        ReferralTargetType targetType,
        Long targetUserId,
        String targetUsername,
        ReferralStatus status,
        String reason,
        Instant dueAt,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {

    public static ReferralResponse from(Referral referral) {
        return new ReferralResponse(
                referral.getId(),
                referral.getRiskCase().getId(),
                referral.getTargetType(),
                referral.getTargetUser() == null ? null : referral.getTargetUser().getId(),
                referral.getTargetUser() == null ? null : referral.getTargetUser().getUsername(),
                referral.getStatus(),
                referral.getReason(),
                referral.getDueAt(),
                referral.getCreatedAt(),
                referral.getUpdatedAt(),
                referral.getCompletedAt());
    }
}
