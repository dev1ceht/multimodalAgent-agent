package com.multimodalAgent.agent.service.mcp;

import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.service.DeliveryIdempotency;
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
    public void write(PsychologicalReport report, String idempotencyKey) {
        String deliveryKey = DeliveryIdempotency.requireKey(idempotencyKey);
        Map<String, Object> payload = new java.util.LinkedHashMap<>(ReportPayloads.from(report));
        payload.put("idempotencyKey", deliveryKey);
        client.callTool("multimodalAgent.excel.write_report", payload);
    }
}
