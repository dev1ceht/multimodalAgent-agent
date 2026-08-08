package com.multimodalAgent.agent.dto;

import java.time.Instant;
import java.util.List;

/** Privacy-preserving school operations projection with population-level counts only. */
public record OperationsOverviewResponse(
        Instant from,
        Instant to,
        Instant generatedAt,
        long activeStudents,
        List<OperationsRiskCount> riskAssessmentsByLevel,
        List<OperationsCaseStatusCount> casesByStatus,
        long activeReferrals,
        long overdueReferrals,
        long interventionsInWindow
) {
}
