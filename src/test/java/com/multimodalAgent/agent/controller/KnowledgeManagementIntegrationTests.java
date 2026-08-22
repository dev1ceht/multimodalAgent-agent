package com.multimodalAgent.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:knowledge-management-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                "management.server.port=0",
                "multimodal-agent.ai.provider=mock",
                "multimodal-agent.knowledge.index-sync.enabled=false",
                "multimodal-agent.security.demo-accounts-enabled=true",
                "multimodal-agent.security.auth-session-store=memory",
                "multimodal-agent.security.jwt-secret=knowledge-management-test-secret-that-is-at-least-32-bytes"
        })
@ActiveProfiles("test")
@AutoConfigureWebTestClient
class KnowledgeManagementIntegrationTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void systemAdminCanManageKnowledgeLifecycleWhileStudentIsDenied() {
        String adminToken = login("admin", "admin123");
        String studentToken = login("student", "student123");

        webTestClient.get()
                .uri("/api/admin/knowledge/documents")
                .headers(headers -> headers.setBearerAuth(studentToken))
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.post()
                .uri("/api/admin/knowledge/documents")
                .headers(headers -> headers.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("source", "managed-test.md", "content", "Initial managed content"))
                .exchange()
                .expectStatus().isOk();

        Long documentId = documents(adminToken).stream()
                .filter(document -> "managed-test.md".equals(document.get("source")))
                .map(document -> ((Number) document.get("id")).longValue())
                .findFirst()
                .orElseThrow();
        long documentVersion = documents(adminToken).stream()
                .filter(document -> "managed-test.md".equals(document.get("source")))
                .map(document -> ((Number) document.get("version")).longValue())
                .findFirst()
                .orElseThrow();

        webTestClient.put()
                .uri("/api/admin/knowledge/documents/{id}", documentId)
                .headers(headers -> headers.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "source", "managed-renamed.md",
                        "content", "Updated managed content",
                        "version", documentVersion))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.source").isEqualTo("managed-renamed.md")
                .jsonPath("$.content").isEqualTo("Updated managed content");

        webTestClient.get()
                .uri("/api/admin/knowledge/versions")
                .headers(headers -> headers.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].latest").isEqualTo(true)
                .jsonPath("$[0].taskStatus").isEqualTo("PENDING");

        webTestClient.delete()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/admin/knowledge/documents/{id}")
                        .queryParam("version", documentVersion + 1)
                        .build(documentId))
                .headers(headers -> headers.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("deleted");

        assertThat(documents(adminToken))
                .noneMatch(document -> "managed-renamed.md".equals(document.get("source")));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> documents(String accessToken) {
        Map<?, ?> body = webTestClient.get()
                .uri("/api/admin/knowledge/documents")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).isNotNull();
        return (List<Map<String, Object>>) body.get("documents");
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
}
