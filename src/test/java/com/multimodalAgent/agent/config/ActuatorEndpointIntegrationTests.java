package com.multimodalAgent.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "spring.datasource.url=jdbc:h2:mem:actuator-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                "multimodal-agent.ai.provider=mock",
                "multimodal-agent.knowledge.index-sync.enabled=false"
        })
class ActuatorEndpointIntegrationTests {

    @LocalManagementPort
    private int managementPort;

    private WebTestClient managementClient;

    @BeforeEach
    void connectToManagementPort() {
        managementClient = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + managementPort)
                .build();
    }

    @Test
    void exposesPrometheusAndHealthProbesButProtectsGeneralMetrics() {
        managementClient.get()
                .uri("/actuator")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$._links.prometheus.href").exists();

        managementClient.get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_PLAIN)
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("jvm_memory_used_bytes"));

        managementClient.get()
                .uri("/actuator/health/liveness")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");

        managementClient.get()
                .uri("/actuator/health/readiness")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");

        managementClient.get()
                .uri("/actuator/metrics")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
