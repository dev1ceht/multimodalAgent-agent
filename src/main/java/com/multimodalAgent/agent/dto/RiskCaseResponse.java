package com.multimodalAgent.agent.dto;

import com.multimodalAgent.agent.domain.RiskCase;
import com.multimodalAgent.agent.domain.RiskCaseSource;
import com.multimodalAgent.agent.domain.RiskCaseStatus;
import com.multimodalAgent.agent.domain.RiskLevel;
import java.time.Instant;

public record RiskCaseResponse(
        Long id,
        Long reportId,
        Long studentUserId,
        String studentUsername,
        RiskLevel riskLevel,
        RiskCaseSource source,
        RiskCaseStatus status,
        String openingReason,
        Instant openedAt,
        Instant updatedAt,
        Instant acknowledgedAt,
        Instant resolvedAt,
        Instant closedAt
) {

    public static RiskCaseResponse from(RiskCase riskCase) {
        return new RiskCaseResponse(
                riskCase.getId(),
                riskCase.getTriggerReport() == null ? null : riskCase.getTriggerReport().getId(),
                riskCase.getStudentUser().getId(),
                riskCase.getStudentUser().getUsername(),
                riskCase.getRiskLevel(),
                riskCase.getSource(),
                riskCase.getStatus(),
                riskCase.getOpeningReason(),
                riskCase.getOpenedAt(),
                riskCase.getUpdatedAt(),
                riskCase.getAcknowledgedAt(),
                riskCase.getResolvedAt(),
                riskCase.getClosedAt());
    }
}
