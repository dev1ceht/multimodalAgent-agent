package com.multimodalAgent.agent.service.mcp;

import com.multimodalAgent.agent.domain.RiskCase;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stable, bounded payloads shared by the external overdue-case notification adapters. */
public final class RiskCasePayloads {

    private RiskCasePayloads() {
    }

    public static Map<String, Object> overdueEscalation(
            RiskCase riskCase,
            String recipient,
            String idempotencyKey
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notificationType", "RISK_CASE_OVERDUE_ESCALATION");
        payload.put("recipient", recipient);
        payload.put("riskCaseId", riskCase.getId());
        payload.put("reportId", riskCase.getTriggerReport() == null
                ? null
                : riskCase.getTriggerReport().getId());
        payload.put("studentUserId", riskCase.getStudentUser() == null
                ? null
                : riskCase.getStudentUser().getId());
        payload.put("riskLevel", riskCase.getRiskLevel() == null
                ? null
                : riskCase.getRiskLevel().name());
        payload.put("caseStatus", riskCase.getStatus() == null
                ? null
                : riskCase.getStatus().name());
        payload.put("slaDueAt", riskCase.getSlaDueAt());
        payload.put("idempotencyKey", idempotencyKey);
        return payload;
    }
}
