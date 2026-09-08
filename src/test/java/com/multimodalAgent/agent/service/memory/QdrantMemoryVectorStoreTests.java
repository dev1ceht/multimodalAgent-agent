package com.multimodalAgent.agent.service.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.service.knowledge.EmbeddingClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class QdrantMemoryVectorStoreTests {
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicBoolean failNextGet = new AtomicBoolean();
    private HttpServer server;
    private QdrantMemoryVectorStore store;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        multimodalAgentProperties properties = new multimodalAgentProperties();
        properties.getMemory().setEnabled(true);
        properties.getKnowledge().setQdrantBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getEmbedding().setDimensions(2);
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        when(embeddings.embed(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of(0.1, 0.2));
        store = new QdrantMemoryVectorStore(properties, embeddings, WebClient.builder());
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    @Test
    void transientInitializationFailureIsRetried() {
        failNextGet.set(true);
        assertThatThrownBy(() -> store.upsert(batch())).isInstanceOf(RuntimeException.class);

        store.upsert(batch());

        assertThat(requests.stream().filter(r -> r.equals("GET /collections/memory-facts")).count())
                .isEqualTo(2);
        assertThat(requests).contains("PUT /collections/memory-facts", "PUT /collections/memory-topics");
    }

    @Test
    void concurrentCallersInitializeEachCollectionOnlyOnce() {
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> store.upsert(batch()));
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> store.upsert(batch()));
        CompletableFuture.allOf(first, second).join();

        assertThat(requests.stream().filter(r -> r.equals("GET /collections/memory-facts")).count())
                .isEqualTo(1);
        assertThat(requests.stream().filter(r -> r.equals("GET /collections/memory-topics")).count())
                .isEqualTo(1);
    }

    private MemoryProjectionBatch batch() {
        return new MemoryProjectionBatch(7L,
                List.of(new MemoryProjectionBatch.Fact(1L, 2L, "事实", Instant.parse("2026-09-08T08:00:00Z"))),
                List.of(new MemoryProjectionBatch.Topic(3L, "topic", "主题", "摘要")),
                List.of(), List.of());
    }

    private void handle(HttpExchange exchange) throws IOException {
        String request = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        requests.add(request);
        int status;
        if ("GET".equals(exchange.getRequestMethod()) && failNextGet.compareAndSet(true, false)) {
            status = 500;
        } else if ("GET".equals(exchange.getRequestMethod())) {
            status = 404;
        } else {
            status = 200;
        }
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
