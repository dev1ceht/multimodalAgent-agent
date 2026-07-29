package com.multimodalAgent.agent.service.routing;

import com.multimodalAgent.agent.domain.RiskLevel;

/**
 * 一次请求的最终路由决策。
 *
 * <p>needsRag 只决定是否使用知识库，riskLevel 只决定心理安全处置强度。
 * 任何非 NONE 风险都会进入 RAG，避免风险处置缺少安全知识。</p>
 */
public record RoutingDecision(
        boolean needsRag,
        RiskLevel riskLevel,
        double confidence,
        String reason
) {
    public RoutingDecision {
        riskLevel = riskLevel == null ? RiskLevel.NONE : riskLevel;
        needsRag = needsRag || riskLevel != RiskLevel.NONE;
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        reason = reason == null || reason.isBlank() ? "未提供路由依据。" : reason.trim();
    }

    public static RoutingDecision ordinary(String reason) {
        return new RoutingDecision(false, RiskLevel.NONE, 1.0, reason);
    }

    public static RoutingDecision highRisk(String reason) {
        return new RoutingDecision(true, RiskLevel.HIGH, 1.0, reason);
    }

    public RoutingDecision withRiskFloor(RiskLevel floor, String additionalReason) {
        if (floor == null || floor.ordinal() <= riskLevel.ordinal()) {
            return this;
        }
        String mergedReason = reason + "；" + additionalReason;
        return new RoutingDecision(true, floor, confidence, mergedReason);
    }
}
