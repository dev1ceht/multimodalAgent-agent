package com.multimodalAgent.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.tracing.TracingProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "management.tracing.enabled=true",
                "management.tracing.sampling.probability=1.0",
                "management.otlp.tracing.endpoint=http://127.0.0.1:1/v1/traces",
                "spring.datasource.url=jdbc:h2:mem:actuator-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                "multimodal-agent.ai.provider=mock",
                "multimodal-agent.knowledge.index-sync.enabled=false"
        })
@ActiveProfiles("test")
class ActuatorEndpointIntegrationTests {

    @Autowired
    private Tracer tracer;

    @Autowired
    private List<WebClientCustomizer> webClientCustomizers;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private WebClient.Builder webClientBuilder;

    @Autowired
    private ObjectProvider<Propagator> propagatorProvider;

    @Autowired
    private List<ObservationHandler<?>> observationHandlers;

    @Autowired
    private TracingProperties tracingProperties;

    @LocalManagementPort
    private int managementPort;

    @LocalServerPort
    private int applicationPort;

    private WebTestClient managementClient;
    private WebTestClient applicationClient;

    @BeforeEach
    void connectToApplicationAndManagementPorts() {
        managementClient = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + managementPort)
                .build();
        applicationClient = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + applicationPort)
                .build();
    }

    @Test
    void exposesSummaryHealthOnTheApplicationPortWithoutAuthentication() {
        applicationClient.get()
                .uri("/api/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
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

    @Test
    void correlatesLogsAndPropagatesW3cTraceContextThroughSharedWebClient() {
        assertThat(webClientCustomizers)
                .extracting(customizer -> customizer.getClass().getName())
                .anyMatch(name -> name.contains("ObservationWebClientCustomizer"));
        assertThat(propagatorProvider.getIfAvailable()).isNotNull();
        assertThat(tracingProperties.getPropagation().getType())
                .containsExactly(TracingProperties.Propagation.PropagationType.W3C);
        assertThat(propagatorProvider.getObject().fields()).contains("traceparent");
        assertThat(observationHandlers)
                .extracting(handler -> handler.getClass().getName())
                .anyMatch(name -> name.contains("PropagatingSenderTracingObservationHandler"));
        AtomicReference<String> traceparent = new AtomicReference<>();
        AtomicReference<String> downstreamHeaders = new AtomicReference<>();
        DisposableServer downstream = HttpServer.create()
                .port(0)
                .handle((request, response) -> {
                    traceparent.set(request.requestHeaders().get("traceparent"));
                    downstreamHeaders.set(request.requestHeaders().toString());
                    return response.sendString(reactor.core.publisher.Mono.just("ok"));
                })
                .bindNow();

        AtomicReference<String> parentTraceId = new AtomicReference<>();
        String result;
        try {
            String downstreamUrl = "http://127.0.0.1:" + downstream.port();
            result = Observation.createNotStarted("trace-propagation-test", observationRegistry)
                    .observe(() -> {
                        String traceId = tracer.currentSpan().context().traceId();
                        parentTraceId.set(traceId);
                        assertThat(MDC.get("traceId")).isEqualTo(traceId);
                        return webClientBuilder.clone()
                                .build()
                                .get()
                                .uri(downstreamUrl)
                                .retrieve()
                                .bodyToMono(String.class)
                                .block();
                    });
        } finally {
            downstream.disposeNow();
        }

        assertThat(result).isEqualTo("ok");
        assertThat(traceparent.get())
                .as("downstream headers: %s", downstreamHeaders.get())
                .matches("00-" + parentTraceId.get() + "-[0-9a-f]{16}-01");
    }
}
