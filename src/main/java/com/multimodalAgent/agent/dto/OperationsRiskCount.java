package com.multimodalAgent.agent.dto;

import com.multimodalAgent.agent.domain.RiskLevel;

/** Aggregate-only risk assessment count; it intentionally carries no report data. */
public record OperationsRiskCount(RiskLevel riskLevel, long count) {
}
