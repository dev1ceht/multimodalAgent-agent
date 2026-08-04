package com.multimodalAgent.agent.service.mcp;

import com.multimodalAgent.agent.domain.AlertRecord;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.service.DeliveryIdempotency;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通过 MCP tools/call 发送高风险预警。
 */
public class McpAlertNotifier implements AlertNotifier {

    private final McpProtocolClient client;

    public McpAlertNotifier(McpProtocolClient client) {
        this.client = client;
    }

    @Override
    public void notify(AlertRecord alertRecord, PsychologicalReport report, String idempotencyKey) {
        String deliveryKey = DeliveryIdempotency.requireKey(idempotencyKey);
        Map<String, Object> payload = new LinkedHashMap<>(ReportPayloads.from(report));
        payload.put("recipient", alertRecord.getRecipient());
        payload.put("alertId", alertRecord.getId());
        payload.put("idempotencyKey", deliveryKey);
        client.callTool("multimodalAgent.email.send_alert", payload);
    }
}
