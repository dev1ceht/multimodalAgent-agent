package com.multimodalAgent.agent.dto;

import com.multimodalAgent.agent.domain.ConsentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ConsentGrantRequest(
        @NotNull ConsentType consentType,
        @NotBlank @Size(max = 40) String version
) {
}
