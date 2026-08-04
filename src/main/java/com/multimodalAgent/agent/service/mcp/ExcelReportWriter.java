package com.multimodalAgent.agent.service.mcp;

import com.multimodalAgent.agent.domain.PsychologicalReport;

/**
 * Interface for writing a psychological report to an Excel ledger.
 */
public interface ExcelReportWriter {

    void write(PsychologicalReport report);

    /**
     * Writes a report while preserving the delivery idempotency key.
     */
    void write(PsychologicalReport report, String idempotencyKey);
}
