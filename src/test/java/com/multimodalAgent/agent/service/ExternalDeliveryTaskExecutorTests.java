package com.multimodalAgent.agent.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.multimodalAgent.agent.domain.AlertRecord;
import com.multimodalAgent.agent.domain.DeliveryTask;
import com.multimodalAgent.agent.domain.DeliveryTaskType;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskCase;
import com.multimodalAgent.agent.service.mcp.AlertNotifier;
import com.multimodalAgent.agent.service.mcp.ExcelReportWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExternalDeliveryTaskExecutorTests {

    @Mock
    private ExcelReportWriter excelReportWriter;

    @Mock
    private AlertNotifier alertNotifier;

    @Mock
    private PsychologicalReport report;

    @Mock
    private AlertRecord alertRecord;

    @Mock
    private RiskCase riskCase;

    @Test
    void dispatchesExcelTaskWithItsIdempotencyKey() {
        DeliveryTask task = task(DeliveryTaskType.EXCEL_EXPORT, "excel:42");

        executor().execute(task);

        verify(excelReportWriter).write(report, "excel:42");
        verify(alertNotifier, never()).notify(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void dispatchesAlertTaskWithItsIdempotencyKey() {
        DeliveryTask task = task(DeliveryTaskType.ALERT_NOTIFICATION, "alert:42:counselor@example.com");

        executor().execute(task);

        verify(alertNotifier).notify(alertRecord, report, "alert:42:counselor@example.com");
        verify(excelReportWriter, never()).write(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void dispatchesRiskCaseEscalationWithItsIdempotencyKey() {
        DeliveryTask task = task(DeliveryTaskType.RISK_CASE_ESCALATION,
                "risk-case-overdue:42:counselor@example.com");

        executor().execute(task);

        verify(alertNotifier).notifyRiskCaseEscalation(
                riskCase, "counselor@example.com", "risk-case-overdue:42:counselor@example.com");
        verify(excelReportWriter, never()).write(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rejectsTaskWithoutAType() {
        DeliveryTask task = task(null, "unknown:42");

        assertThatThrownBy(() -> executor().execute(task))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unsupported delivery task type: null");
    }

    @Test
    void rejectsTaskWithoutAnIdempotencyKey() {
        assertThatThrownBy(() -> executor().execute(task(DeliveryTaskType.EXCEL_EXPORT, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Delivery task idempotency key must not be blank");

        assertThatThrownBy(() -> executor().execute(task(DeliveryTaskType.EXCEL_EXPORT, " ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Delivery task idempotency key must not be blank");
    }

    private DeliveryTaskExecutor executor() {
        return new ExternalDeliveryTaskExecutor(excelReportWriter, alertNotifier);
    }

    private DeliveryTask task(DeliveryTaskType type, String idempotencyKey) {
        DeliveryTask task = new DeliveryTask();
        task.setTaskType(type);
        task.setReport(report);
        task.setIdempotencyKey(idempotencyKey);
        if (type == DeliveryTaskType.ALERT_NOTIFICATION) {
            task.setAlertRecord(alertRecord);
        }
        if (type == DeliveryTaskType.RISK_CASE_ESCALATION) {
            task.setRiskCase(riskCase);
            task.setRecipient("counselor@example.com");
        }
        return task;
    }
}
