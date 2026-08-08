package com.multimodalAgent.agent.service.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskCase;
import com.multimodalAgent.agent.domain.RiskCaseStatus;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.UserAccount;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RiskCasePayloadsTests {

    @Test
    void buildsAnOverduePayloadWithoutReportContentOrStaffNotes() {
        RiskCase riskCase = mock(RiskCase.class);
        PsychologicalReport report = mock(PsychologicalReport.class);
        UserAccount student = mock(UserAccount.class);
        when(riskCase.getId()).thenReturn(42L);
        when(riskCase.getTriggerReport()).thenReturn(report);
        when(riskCase.getStudentUser()).thenReturn(student);
        when(riskCase.getRiskLevel()).thenReturn(RiskLevel.HIGH);
        when(riskCase.getStatus()).thenReturn(RiskCaseStatus.OPEN);
        when(riskCase.getSlaDueAt()).thenReturn(Instant.parse("2026-08-08T02:00:00Z"));
        when(report.getId()).thenReturn(7L);
        when(student.getId()).thenReturn(9L);

        Map<String, Object> payload = RiskCasePayloads.overdueEscalation(
                riskCase, "ops@example.com", "risk-case-overdue:42:ops@example.com");

        assertThat(payload)
                .containsEntry("notificationType", "RISK_CASE_OVERDUE_ESCALATION")
                .containsEntry("riskCaseId", 42L)
                .containsEntry("reportId", 7L)
                .containsEntry("studentUserId", 9L)
                .containsEntry("riskLevel", "HIGH")
                .containsEntry("caseStatus", "OPEN")
                .containsEntry("recipient", "ops@example.com")
                .doesNotContainKeys("content", "summary", "staffNotes");
    }
}
