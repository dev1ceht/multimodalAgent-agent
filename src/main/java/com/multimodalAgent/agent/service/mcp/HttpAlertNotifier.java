package com.multimodalAgent.agent.service.mcp;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.AlertRecord;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.service.DeliveryIdempotency;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 通过 HTTP MCP 服务发送高风险预警。
 */
public class HttpAlertNotifier implements AlertNotifier {

    private final WebClient webClient;

    public HttpAlertNotifier(WebClient.Builder webClientBuilder, multimodalAgentProperties properties) {
        this.webClient = webClientBuilder.clone()
                .baseUrl(properties.getMcp().getEmail().getUrl())
                .build();
    }

    @Override
    public void notify(AlertRecord alertRecord, PsychologicalReport report, String idempotencyKey) {
        String deliveryKey = DeliveryIdempotency.requireKey(idempotencyKey);
        var request = webClient.post().uri("/send");
        request = request.header("Idempotency-Key", deliveryKey);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recipient", alertRecord.getRecipient());
        payload.put("reportId", report.getId());
        payload.put("userId", report.getUser().getId());
        payload.put("username", report.getUser().getUsername());
        payload.put("riskLevel", report.getRiskLevel().name());
        payload.put("summary", report.getSummary());
        payload.put("content", report.getContent());
        payload.put("idempotencyKey", deliveryKey);
        request.bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .block();
    }
}
