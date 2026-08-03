package com.multimodalAgent.agent.service.mcp;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 通过 HTTP MCP 服务写入 Excel 记录。
 */
public class HttpExcelReportWriter implements ExcelReportWriter {

    private final WebClient webClient;

    public HttpExcelReportWriter(WebClient.Builder webClientBuilder, multimodalAgentProperties properties) {
        this.webClient = webClientBuilder.baseUrl(properties.getMcp().getExcel().getUrl()).build();
    }

    @Override
    public void write(PsychologicalReport report) {
        write(report, null);
    }

    @Override
    public void write(PsychologicalReport report, String idempotencyKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportId", report.getId());
        payload.put("userId", report.getUser().getId());
        payload.put("username", report.getUser().getUsername());
        payload.put("sessionId", report.getSession() == null ? "" : report.getSession().getPublicId());
        payload.put("needsRag", report.isNeedsRag());
        payload.put("emotion", report.getEmotion().name());
        payload.put("emotionScore", report.getEmotionScore());
        payload.put("riskLevel", report.getRiskLevel().name());
        payload.put("confidence", report.getConfidence());
        payload.put("summary", report.getSummary());
        payload.put("content", report.getContent());
        payload.put("createdAt", report.getCreatedAt().toString());
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            payload.put("idempotencyKey", idempotencyKey);
        }

        var request = webClient.post().uri("/write");
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        request.bodyValue(payload).retrieve().toBodilessEntity().block();
    }
}
