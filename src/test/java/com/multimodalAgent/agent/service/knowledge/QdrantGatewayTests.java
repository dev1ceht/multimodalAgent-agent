package com.multimodalAgent.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class QdrantGatewayTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private QdrantGateway gateway;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        multimodalAgentProperties properties = new multimodalAgentProperties();
        properties.getKnowledge().setQdrantBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        gateway = new QdrantGateway(properties, WebClient.builder());
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    @Test
    void createsCollectionUpsertsPayloadAndValidatesCount() throws Exception {
        gateway.prepareVersionIndex("mindcare-knowledge-v1", 2);
        gateway.indexVersionChunk("mindcare-knowledge-v1", "vector-7", 7L, "v1", "sleep.md", 2,
                "Sleep support guidance.", "Sleep > Daily routine\nSleep support guidance.",
                "parent-1", 0, "Sleep > Daily routine", 10, 33, 2, 2, List.of(0.1, 0.2));

        assertThat(gateway.refreshAndCount("mindcare-knowledge-v1")).isEqualTo(1L);
        JsonNode create = body("PUT", "/collections/mindcare-knowledge-v1");
        assertThat(create.path("vectors").path("size").asInt()).isEqualTo(2);
        assertThat(create.path("vectors").path("distance").asText()).isEqualTo("Cosine");
        JsonNode point = body("PUT", "/collections/mindcare-knowledge-v1/points").path("points").path(0);
        assertThat(point.path("vector")).hasSize(2);
        assertThat(point.path("payload").path("content").asText()).isEqualTo("Sleep support guidance.");
        assertThat(point.path("payload").path("parent_key").asText()).isEqualTo("parent-1");
    }

    @Test
    void queriesDenseVectorAndMapsQdrantPoints() throws Exception {
        List<SearchResult> results = gateway.vectorSearch(new QdrantQuery(
                "mindcare-knowledge-v1", "睡眠焦虑", List.of(0.1, 0.2), 20));
        JsonNode query = body("POST", "/collections/mindcare-knowledge-v1/points/query");
        assertThat(query.path("query")).hasSize(2);
        assertThat(query.path("limit").asInt()).isEqualTo(20);
        assertThat(query.path("with_payload").asBoolean()).isTrue();
        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.chunkId()).isEqualTo(7L);
            assertThat(result.source()).isEqualTo("sleep.md");
            assertThat(result.score()).isEqualTo(0.82);
            assertThat(result.provenance().parentKey()).isEqualTo("parent-1");
        });
    }

    private JsonNode body(String method, String path) throws Exception {
        return mapper.readTree(requests.stream().filter(r -> r.method.equals(method) && r.path.equals(path))
                .findFirst().orElseThrow().body);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.add(new CapturedRequest(exchange.getRequestMethod(), path,
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        int status = exchange.getRequestMethod().equals("DELETE") ? 404 : 200;
        String response = "{\"result\":{\"status\":\"ok\"}}";
        if (path.endsWith("/points/count")) response = "{\"result\":{\"count\":1}}";
        if (path.endsWith("/points/query")) response = """
                {"result":{"points":[{"id":"x","score":0.82,"payload":{
                "chunk_id":7,"source":"sleep.md","source_index":2,"content":"Sleep support guidance.",
                "parent_key":"parent-1","section_path":"Sleep > Daily routine","version_key":"v1","vector_id":"vector-7"}}]}}
                """;
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record CapturedRequest(String method, String path, String body) {}
}
