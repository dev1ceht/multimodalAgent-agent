package com.multimodalAgent.agent.dto;

import jakarta.validation.constraints.Size;

public record StudentProfileUpdateRequest(
        @Size(max = 20) String gender,
        @Size(max = 128) String phone,
        @Size(max = 128) String emergencyContactMasked
) {
}
