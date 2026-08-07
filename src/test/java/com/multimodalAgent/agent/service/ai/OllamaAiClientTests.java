package com.multimodalAgent.agent.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class OllamaAiClientTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
    private HttpServer server;
    private OllamaAiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            capturedRequest.set(objectMapper.readTree(exchange.getRequestBody()));
            byte[] response = """
                    {"message":{"role":"assistant","content":"{}"},"done":true}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        multimodalAgentProperties properties = new multimodalAgentProperties();
        properties.getAi().getOllama().setBaseUrl(
                "http://127.0.0.1:" + server.getAddress().getPort());
        client = new OllamaAiClient(properties, WebClient.builder());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void structuredCompletionSendsJsonSchemaToOllama() {
        client.completeJson(
                List.of(AiMessage.user("判断是否需要知识库")),
                StructuredOutputSchemas.routing());

        JsonNode request = capturedRequest.get();
        assertEquals("object", request.path("format").path("type").asText());
        assertTrue(request.path("format").path("required").toString().contains("needsRag"));
        assertFalse(request.path("format").path("additionalProperties").asBoolean(true));
    }

    @Test
    void ordinaryCompletionDoesNotForceJsonOutput() {
        client.complete(List.of(AiMessage.user("自然回答这个问题")));

        assertFalse(capturedRequest.get().has("format"));
    }
}
