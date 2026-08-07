package com.multimodalAgent.agent.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * Ollama 原生聊天接口适配器。
 *
 * <p>直接发送 {@code think:false}，确保 Qwen3/3.5 的硬关闭思考模式进入冻结评测配置。
 * Spring AI 1.0 的 Ollama 请求结构尚不包含该字段，因此这里保留项目内部的
 * {@link AiClient} 接口并只替换传输适配层。</p>
 */
public class OllamaAiClient implements AiClient {

    private final multimodalAgentProperties properties;
    private final WebClient webClient;

    public OllamaAiClient(
            multimodalAgentProperties properties,
            WebClient.Builder webClientBuilder
    ) {
        this.properties = properties;
        this.webClient = webClientBuilder.clone()
                .baseUrl(properties.getAi().getOllama().getBaseUrl())
                .build();
    }

    @Override
    public String complete(List<AiMessage> messages) {
        return complete(messages, null);
    }

    @Override
    public String completeJson(List<AiMessage> messages, Map<String, Object> schema) {
        return complete(messages, schema);
    }

    private String complete(List<AiMessage> messages, Map<String, Object> schema) {
        JsonNode response = webClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request(messages, false, schema))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
        return response == null ? "" : response.path("message").path("content").asText("");
    }

    @Override
    public Flux<String> stream(List<AiMessage> messages) {
        return webClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_NDJSON)
                .bodyValue(request(messages, true, null))
                .retrieve()
                .bodyToFlux(JsonNode.class)
                .map(node -> node.path("message").path("content").asText(""))
                .filter(token -> !token.isBlank());
    }

    private Map<String, Object> request(
            List<AiMessage> messages,
            boolean stream,
            Map<String, Object> schema
    ) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", properties.getAi().getOllama().getModel());
        request.put("messages", messages.stream()
                .map(message -> Map.of(
                        "role", message.role(),
                        "content", message.content()))
                .toList());
        request.put("stream", stream);
        request.put("think", false);
        request.put("keep_alive", "5m");
        request.put("options", Map.of(
                "temperature", properties.getAi().getTemperature(),
                "top_p", 0.85,
                "repeat_penalty", 1.12,
                "num_predict", properties.getAi().getMaxTokens(),
                "num_ctx", properties.getAi().getContextWindow()));
        if (schema != null && !schema.isEmpty()) {
            request.put("format", schema);
        }
        return request;
    }
}
