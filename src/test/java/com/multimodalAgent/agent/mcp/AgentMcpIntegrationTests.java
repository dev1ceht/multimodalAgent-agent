package com.multimodalAgent.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Protocol-level HTTP coverage for the isolated Agent MCP endpoint. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:agent-mcp-http-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                "management.server.port=0",
                "multimodal-agent.ai.provider=mock",
                "multimodal-agent.knowledge.index-sync.enabled=false",
                "multimodal-agent.security.demo-accounts-enabled=true",
                "multimodal-agent.security.auth-session-store=memory",
                "multimodal-agent.security.jwt-secret=agent-mcp-http-test-secret-that-is-at-least-32-bytes"
        })
@ActiveProfiles("test")
@AutoConfigureWebTestClient
class AgentMcpIntegrationTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void realHttpLifecycleListsOnlyAllowlistedToolAndReturnsStudentProjection() {
        String token = login("student", "student123");
        grantConsent(token, "PRIVACY_NOTICE");
        grantConsent(token, "SENSITIVE_DATA_PROCESSING");

        webTestClient.post()
                .uri("/agent-mcp")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "jsonrpc", "2.0",
                        "id", 1,
                        "method", "initialize",
                        "params", Map.of(
                                "protocolVersion", "2025-03-26",
                                "capabilities", Map.of(),
                                "clientInfo", Map.of("name", "http-test", "version", "1.0"))))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.protocolVersion").isEqualTo("2025-03-26")
                .jsonPath("$.result.capabilities.tools").exists();

        webTestClient.post()
                .uri("/agent-mcp")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "jsonrpc", "2.0",
                        "id", 2,
                        "method", "tools/list",
                        "params", Map.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.tools").isArray()
                .jsonPath("$.result.tools.length()").isEqualTo(1)
                .jsonPath("$.result.tools[0].name").isEqualTo("get_support_status")
                .jsonPath("$.result.tools[0].inputSchema.additionalProperties").isEqualTo(false);

        webTestClient.post()
                .uri("/agent-mcp")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "jsonrpc", "2.0",
                        "id", 3,
                        "method", "tools/call",
                        "params", Map.of("name", "get_support_status", "arguments", Map.of())))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false)
                .jsonPath("$.result.content[0].type").isEqualTo("text")
                .jsonPath("$.result.content[0].text").isEqualTo("[]");
    }

    @Test
    void endpointRequiresBearerAuthenticationAndBusinessFailureIsMcpIsError() {
        webTestClient.post()
                .uri("/agent-mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "jsonrpc", "2.0",
                        "id", 1,
                        "method", "tools/list",
                        "params", Map.of()))
                .exchange()
                .expectStatus().isUnauthorized();

        String adminToken = login("admin", "admin123");
        webTestClient.post()
                .uri("/agent-mcp")
                .headers(headers -> headers.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "jsonrpc", "2.0",
                        "id", "denied",
                        "method", "tools/call",
                        "params", Map.of("name", "get_support_status", "arguments", Map.of())))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(true)
                .jsonPath("$.result.content[0].text").isEqualTo("not_authorized");
    }

    private String login(String username, String password) {
        Map<?, ?> body = webTestClient.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", password))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).isNotNull();
        return (String) body.get("accessToken");
    }

    private void grantConsent(String token, String consentType) {
        webTestClient.post()
                .uri("/api/student/consents")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("consentType", consentType, "version", "2026-01"))
                .exchange()
                .expectStatus().isOk();
    }
}
