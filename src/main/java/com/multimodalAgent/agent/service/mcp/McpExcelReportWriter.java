package com.multimodalAgent.agent.service.mcp;

import com.multimodalAgent.agent.domain.PsychologicalReport;
import java.util.Map;

/**
 * 通过 MCP tools/call 写入 Excel。
 */
public class McpExcelReportWriter implements ExcelReportWriter {

    private final McpProtocolClient client;

    public McpExcelReportWriter(McpProtocolClient client) {
        this.client = client;
    }

    @Override
    public void write(PsychologicalReport report) {
        write(report, null);
    }

    @Override
    public void write(PsychologicalReport report, String idempotencyKey) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(ReportPayloads.from(report));
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            payload.put("idempotencyKey", idempotencyKey);
        }
        client.callTool("multimodalAgent.excel.write_report", payload);
    }
}
