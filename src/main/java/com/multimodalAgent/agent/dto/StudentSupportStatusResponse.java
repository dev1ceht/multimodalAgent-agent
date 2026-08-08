package com.multimodalAgent.agent.dto;

import com.multimodalAgent.agent.domain.RiskCaseStatus;
import java.time.Instant;

/** Deliberately small student-facing projection; it contains no staff notes or internal rationale. */
public record StudentSupportStatusResponse(
        Long caseId,
        RiskCaseStatus status,
        boolean hasActiveReferral,
        Instant updatedAt
) {
}
