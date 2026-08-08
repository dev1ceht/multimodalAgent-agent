package com.multimodalAgent.agent.dto;

import com.multimodalAgent.agent.domain.InterventionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record InterventionCreateRequest(
        @NotNull InterventionType type,
        @NotBlank @Size(max = 4000) String notes,
        @Size(max = 500) String outcome,
        @NotNull Instant occurredAt,
        Instant followUpAt
) {
}
