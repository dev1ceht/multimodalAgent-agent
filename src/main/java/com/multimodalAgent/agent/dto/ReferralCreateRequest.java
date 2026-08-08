package com.multimodalAgent.agent.dto;

import com.multimodalAgent.agent.domain.ReferralTargetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record ReferralCreateRequest(
        @NotNull ReferralTargetType targetType,
        @Positive Long targetUserId,
        @NotBlank @Size(max = 240) String reason,
        Instant dueAt
) {
}
