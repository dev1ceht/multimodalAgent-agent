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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class ElasticsearchGatewayTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final List<CapturedRequest> lifecycleRequests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ElasticsearchGateway gateway;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mindcare-knowledge-v1/_search", this::handleSearch);
        server.createContext("/", this::handleLifecycle);
        server.start();
        multimodalAgentProperties properties = new multimodalAgentProperties();
        properties.getKnowledge().setElasticsearchBaseUrl(
                "http://127.0.0.1:" + server.getAddress().getPort());
        gateway = new ElasticsearchGateway(properties, WebClient.builder());
    }

    @Test
    void createsVersionIndexIndexesChunksValidatesCountAndSwitchesAlias() throws Exception {
        gateway.prepareVersionIndex("mindcare-knowledge-v1", 2);
        gateway.indexVersionChunk(
                "mindcare-knowledge-v1",
                "vector-7",
                7L,
                "v1",
                "sleep.md",
                2,
                "Sleep support guidance.",
                List.of(0.1, 0.2));

        long count = gateway.refreshAndCount("mindcare-knowledge-v1");
        gateway.activateAlias("mindcare-knowledge-v1", "mindcare-knowledge-active");

        assertThat(count).isEqualTo(1L);
        CapturedRequest createIndex = lifecycleRequests.stream()
                .filter(request -> request.method().equals("PUT"))
                .filter(request -> request.path().equals("/mindcare-knowledge-v1"))
                .findFirst()
                .orElseThrow();
        JsonNode mapping = objectMapper.readTree(createIndex.body())
                .path("mappings").path("properties");
        assertThat(mapping.path("content").path("type").asText()).isEqualTo("text");
        assertThat(mapping.path("content_vector").path("type").asText()).isEqualTo("dense_vector");
        assertThat(mapping.path("content_vector").path("dims").asInt()).isEqualTo(2);
        assertThat(mapping.path("content_vector").path("similarity").asText()).isEqualTo("cosine");

        CapturedRequest indexedChunk = lifecycleRequests.stream()
                .filter(request -> request.path().equals("/mindcare-knowledge-v1/_doc/vector-7"))
                .findFirst()
                .orElseThrow();
        JsonNode chunk = objectMapper.readTree(indexedChunk.body());
        assertThat(chunk.path("content").asText()).isEqualTo("Sleep support guidance.");
        assertThat(chunk.path("content_vector")).hasSize(2);

        CapturedRequest aliasSwitch = lifecycleRequests.stream()
                .filter(request -> request.path().equals("/_aliases"))
                .findFirst()
                .orElseThrow();
        JsonNode actions = objectMapper.readTree(aliasSwitch.body()).path("actions");
        assertThat(actions).singleElement().satisfies(action -> {
            assertThat(action.path("add").path("index").asText()).isEqualTo("mindcare-knowledge-v1");
            assertThat(action.path("add").path("alias").asText()).isEqualTo("mindcare-knowledge-active");
        });
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsKnnAndBm25AsRrfRetrieversAndMapsRankedHits() throws Exception {
        ElasticsearchHybridQuery query = new ElasticsearchHybridQuery(
                "mindcare-knowledge-v1",
                "睡眠焦虑",
                List.of(0.1, 0.2),
                30,
                120,
                50,
                60,
                20);

        List<SearchResult> results = gateway.hybridSearch(query);

        JsonNode request = objectMapper.readTree(requestBody.get());
        JsonNode rrf = request.path("retriever").path("rrf");
        assertThat(rrf.path("rank_window_size").asInt()).isEqualTo(50);
        assertThat(rrf.path("rank_constant").asInt()).isEqualTo(60);
        assertThat(rrf.path("retrievers")).hasSize(2);
        assertThat(rrf.path("retrievers").path(0)
                .path("standard").path("query").path("match").path("content").path("query").asText())
                .isEqualTo("睡眠焦虑");
        assertThat(rrf.path("retrievers").path(0).path("standard").path("search_after").isMissingNode())
                .isTrue();
        JsonNode knn = rrf.path("retrievers").path(1).path("knn");
        assertThat(knn.path("field").asText()).isEqualTo("content_vector");
        assertThat(knn.path("k").asInt()).isEqualTo(30);
        assertThat(knn.path("num_candidates").asInt()).isEqualTo(120);
        assertThat(knn.path("query_vector")).hasSize(2);
        assertThat(request.path("size").asInt()).isEqualTo(20);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.chunkId()).isEqualTo(7L);
            assertThat(result.source()).isEqualTo("sleep.md");
            assertThat(result.content()).isEqualTo("Sleep support guidance.");
            // Elasticsearch returns the raw RRF score. The adapter normalizes the
            // two-retriever theoretical maximum to 1.0 for the existing evidence policy.
            assertThat(result.score()).isEqualTo(1.0);
            assertThat(result.provenance())
                    .isEqualTo(new EvidenceProvenance("v1", "vector-7", 2));
        });
    }

    private void handleSearch(HttpExchange exchange) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] response = """
                {
                  "hits": {
                    "hits": [
                      {
                        "_id": "vector-7",
                        "_score": 0.0328,
                        "_source": {
                          "chunk_id": 7,
                          "source": "sleep.md",
                          "source_index": 2,
                          "content": "Sleep support guidance.",
                          "version_key": "v1",
                          "vector_id": "vector-7"
                        }
                      }
                    ]
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void handleLifecycle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lifecycleRequests.add(new CapturedRequest(exchange.getRequestMethod(), path, body));
        int status = 200;
        String response = "{\"acknowledged\":true}";
        if (exchange.getRequestMethod().equals("DELETE") && path.equals("/mindcare-knowledge-v1")) {
            status = 404;
            response = "{\"error\":\"index_not_found_exception\"}";
        } else if (path.equals("/mindcare-knowledge-v1/_count")) {
            response = "{\"count\":1}";
        } else if (path.equals("/_alias/mindcare-knowledge-active")) {
            status = 404;
            response = "{\"error\":\"alias_missing_exception\"}";
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record CapturedRequest(String method, String path, String body) {
    }
}
