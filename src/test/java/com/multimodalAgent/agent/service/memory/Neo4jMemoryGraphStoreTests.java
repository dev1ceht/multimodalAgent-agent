package com.multimodalAgent.agent.service.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class Neo4jMemoryGraphStoreTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private Neo4jMemoryGraphStore store;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        multimodalAgentProperties properties = new multimodalAgentProperties();
        properties.getMemory().setNeo4jBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getMemory().setNeo4jUsername("neo4j");
        properties.getMemory().setNeo4jPassword("secret");
        store = new Neo4jMemoryGraphStore(properties, WebClient.builder());
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    @Test
    void projectsFactsTopicsMembershipsAndRelationsThroughCurrentQueryApi() throws Exception {
        store.upsert(new MemoryProjectionBatch(3L,
                List.of(new MemoryProjectionBatch.Fact(10L, 8L, "答辩改到周五", Instant.parse("2026-09-11T08:00:00Z"))),
                List.of(new MemoryProjectionBatch.Topic(20L, "defense", "答辩", "答辩安排")),
                List.of(new MemoryProjectionBatch.Membership(10L, 20L)),
                List.of(new MemoryProjectionBatch.Relation(10L, 11L, MemoryRelationType.CAUSES, 0.9))));

        assertThat(requests).hasSize(4).allSatisfy(request -> {
            assertThat(request.path()).isEqualTo("/db/neo4j/query/v2");
            assertThat(request.authorization()).startsWith("Basic ");
        });
        assertThat(mapper.readTree(requests.get(0).body()).path("statement").asText())
                .contains("MERGE (f:Fact");
        assertThat(mapper.readTree(requests.get(3).body()).path("parameters").path("rows").path(0)
                .path("type").asText()).isEqualTo("CAUSES");
        assertThat(mapper.readTree(requests.get(1).body()).path("statement").asText())
                .contains("t.projectionRevision <= row.projectionRevision");
    }

    @Test
    void mapsMultiHopQueryApiRowsToClosedRelationshipTypes() {
        List<MemoryGraphHit> hits = store.expand(3L, List.of(10L), 3);

        assertThat(hits).containsExactly(new MemoryGraphHit(11L, "复查数据", MemoryRelationType.ELABORATES, 2,
                "周五参加答辩 -[ELABORATES]-> 导师要求复查；导师要求复查 <-[CAUSES]- 复查数据"));
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo("/db/neo4j/query/v2");
            assertThat(request.body()).contains("MEMORY_RELATION*1..3", "WITH p,fact");
        });
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new CapturedRequest(exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"), body));
        String response = body.contains("RETURN DISTINCT")
                ? "{\"data\":{\"fields\":[\"id\",\"content\",\"nodeIds\",\"nodeContents\","
                        + "\"types\",\"sources\",\"targets\",\"depth\"],"
                        + "\"values\":[[11,\"复查数据\",[10,12,11],[\"周五参加答辩\",\"导师要求复查\",\"复查数据\"],"
                        + "[\"ELABORATES\",\"CAUSES\"],[10,11],[12,12],2]]}}"
                : "{\"data\":{\"fields\":[],\"values\":[]}}";
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(202, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record CapturedRequest(String path, String authorization, String body) {}
}
