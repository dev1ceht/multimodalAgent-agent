package com.multimodalAgent.agent.service.agentruntime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable, non-sensitive result envelope returned to the model by every project tool. */
public record AgentToolResult(
        String status,
        Map<String, Object> data,
        String errorCode,
        boolean retryable
) {

    public AgentToolResult {
        status = status == null || status.isBlank() ? "failed" : status.trim();
        data = data == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(data));
        errorCode = errorCode == null ? "" : errorCode.trim();
    }

    public static AgentToolResult success(Map<String, Object> data) {
        return new AgentToolResult("success", data, "", false);
    }

    public static AgentToolResult empty(String code) {
        return new AgentToolResult("empty", Map.of(), code, false);
    }

    public static AgentToolResult failed(String code, boolean retryable) {
        return new AgentToolResult("failed", Map.of(), code, retryable);
    }

    public static AgentToolResult denied(String code) {
        return new AgentToolResult("denied", Map.of(), code, false);
    }

    public String toJson(ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "status", status,
                    "data", data,
                    "errorCode", errorCode,
                    "retryable", retryable));
        } catch (JsonProcessingException exception) {
            return "{\"status\":\"failed\",\"data\":{},\"errorCode\":\"serialization_failed\",\"retryable\":false}";
        }
    }

    public static Map<String, Object> listData(String key, List<?> values) {
        return Map.of(key, values == null ? List.of() : List.copyOf(values));
    }
}
