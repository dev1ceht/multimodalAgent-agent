package com.multimodalAgent.agent.service.mcp;

import com.multimodalAgent.agent.domain.AlertRecord;
import com.multimodalAgent.agent.domain.PsychologicalReport;

/**
 * Interface for delivering a high-risk alert.
 */
public interface AlertNotifier {

    /**
     * Delivers an alert with its delivery idempotency key.
     */
    void notify(AlertRecord alertRecord, PsychologicalReport report, String idempotencyKey);
}
