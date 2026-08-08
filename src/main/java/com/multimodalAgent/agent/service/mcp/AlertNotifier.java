package com.multimodalAgent.agent.service.mcp;

import com.multimodalAgent.agent.domain.AlertRecord;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskCase;

/**
 * Interface for delivering a high-risk alert.
 */
public interface AlertNotifier {

    /**
     * Delivers an alert with its delivery idempotency key.
     */
    void notify(AlertRecord alertRecord, PsychologicalReport report, String idempotencyKey);

    /** Delivers a one-time signal that an active risk case missed its response deadline. */
    default void notifyRiskCaseEscalation(RiskCase riskCase, String recipient, String idempotencyKey) {
        throw new UnsupportedOperationException("Risk-case escalation notification is not configured");
    }
}
