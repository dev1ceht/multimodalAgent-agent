package com.multimodalAgent.agent.dto;

import com.multimodalAgent.agent.domain.RiskCaseStatus;

/** Aggregate-only case status count; it intentionally carries no case identity. */
public record OperationsCaseStatusCount(RiskCaseStatus status, long count) {
}
