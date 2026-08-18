package com.multimodalAgent.agent.service.multimodal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

class WhisperClientTests {

    private final AtomicReference<String> requestContentType = new AtomicReference<>();
    private final AtomicReference<byte[]> requestBody = new AtomicReference<>();
    private HttpServer server;
    private WhisperClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/audio/transcriptions", exchange -> {
            requestContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(exchange.getRequestBody().readAllBytes());
            byte[] response = "{\"text\":\"transcribed recording\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        multimodalAgentProperties properties = new multimodalAgentProperties();
        properties.getMultimodal().getWhisper().setMode("openai");
        properties.getMultimodal().getWhisper().setApiKey("test-key");
        properties.getMultimodal().getWhisper().setBaseUrl(
                "http://127.0.0.1:" + server.getAddress().getPort());
        client = new WhisperClient(properties, WebClient.builder());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void forwardsRecordingFilenameAndContentTypeThroughWhisperMultipartRequest() {
        FilePart audio = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("audio/webm"));
        when(audio.filename()).thenReturn("recording.webm");
        when(audio.headers()).thenReturn(headers);
        when(audio.content()).thenReturn(Flux.just(
                new DefaultDataBufferFactory().wrap(new byte[]{1, 2, 3})));

        String transcript = client.transcribe(audio).block(Duration.ofSeconds(5));

        assertThat(transcript).isEqualTo("transcribed recording");
        assertThat(requestContentType.get()).startsWith("multipart/form-data;");
        String body = new String(requestBody.get(), StandardCharsets.ISO_8859_1);
        assertThat(body)
                .contains("filename=\"recording.webm\"")
                .contains("Content-Type: audio/webm")
                .contains("name=\"model\"");
    }
}
