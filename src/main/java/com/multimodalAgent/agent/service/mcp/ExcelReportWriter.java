package com.multimodalAgent.agent.service.mcp;

import com.multimodalAgent.agent.domain.PsychologicalReport;

/**
 * Interface for writing a psychological report to an Excel ledger.
 */
public interface ExcelReportWriter {

    /**
     * Writes a report with its delivery idempotency key.
     */
    void write(PsychologicalReport report, String idempotencyKey);
}
