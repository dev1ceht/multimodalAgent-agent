package com.multimodalAgent.agent.dto;

import com.multimodalAgent.agent.domain.RiskCaseStatus;
import jakarta.validation.constraints.NotNull;

public record RiskCaseStatusRequest(@NotNull RiskCaseStatus status, Long expectedVersion) {
}
