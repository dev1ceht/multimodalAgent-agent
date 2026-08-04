package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.domain.DeliveryTask;
import com.multimodalAgent.agent.domain.DeliveryTaskType;
import com.multimodalAgent.agent.service.mcp.AlertNotifier;
import com.multimodalAgent.agent.service.mcp.ExcelReportWriter;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Adapter that maps persisted task types to the external tool interfaces.
 */
@Service
public class ExternalDeliveryTaskExecutor implements DeliveryTaskExecutor {

    private final ExcelReportWriter excelReportWriter;
    private final AlertNotifier alertNotifier;

    public ExternalDeliveryTaskExecutor(
            ExcelReportWriter excelReportWriter,
            AlertNotifier alertNotifier
    ) {
        this.excelReportWriter = excelReportWriter;
        this.alertNotifier = alertNotifier;
    }

    @Override
    public void execute(DeliveryTask task) {
        Objects.requireNonNull(task, "task");
        String idempotencyKey = DeliveryIdempotency.requireKey(task.getIdempotencyKey());
        DeliveryTaskType taskType = task.getTaskType();
        if (taskType == null) {
            throw new IllegalStateException("Unsupported delivery task type: null");
        }
        switch (taskType) {
            case EXCEL_EXPORT -> excelReportWriter.write(
                    task.getReport(),
                    idempotencyKey);
            case ALERT_NOTIFICATION -> alertNotifier.notify(
                    task.getAlertRecord(),
                    task.getReport(),
                    idempotencyKey);
            default -> throw new IllegalStateException("Unsupported delivery task type: " + taskType);
        }
    }
}
