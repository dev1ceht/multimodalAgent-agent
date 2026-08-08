package com.multimodalAgent.agent.dto;

import com.multimodalAgent.agent.domain.ReferralStatus;
import jakarta.validation.constraints.NotNull;

public record ReferralStatusRequest(@NotNull ReferralStatus status) {
}
