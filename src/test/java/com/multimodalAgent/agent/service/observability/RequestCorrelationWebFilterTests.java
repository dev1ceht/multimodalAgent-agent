package com.multimodalAgent.agent.service.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class RequestCorrelationWebFilterTests {

    @Mock
    private OperationalMetrics operationalMetrics;

    @Test
    void preservesSafeRequestIdAndExposesItToReactiveContext() {
        RequestCorrelationWebFilter filter = new RequestCorrelationWebFilter(operationalMetrics);
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/chat")
                        .header(RequestCorrelationWebFilter.REQUEST_ID_HEADER, "client-42")
                        .build());
        AtomicReference<String> contextRequestId = new AtomicReference<>();
        WebFilterChain chain = current -> Mono.deferContextual(context -> {
            current.getAttributes().put(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/chat");
            contextRequestId.set(context.get(RequestCorrelationWebFilter.REQUEST_ID_CONTEXT_KEY));
            current.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.empty();
        });

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getHeaders().getFirst(
                RequestCorrelationWebFilter.REQUEST_ID_HEADER)).isEqualTo("client-42");
        assertThat(contextRequestId).hasValue("client-42");
        verify(operationalMetrics).recordHttpRequest(eq("GET"), eq("/api/chat"), eq(200), anyLong());
    }

    @Test
    void replacesUnsafeRequestIdAndUsesUnmatchedRouteWithoutLeakingRawPath() {
        RequestCorrelationWebFilter filter = new RequestCorrelationWebFilter(operationalMetrics);
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/chat/user-123")
                        .header(RequestCorrelationWebFilter.REQUEST_ID_HEADER, "bad id")
                        .build());

        filter.filter(exchange, current -> Mono.empty()).block();

        String requestId = exchange.getResponse().getHeaders().getFirst(
                RequestCorrelationWebFilter.REQUEST_ID_HEADER);
        assertThat(requestId).matches("[A-Za-z0-9._-]{1,64}");
        assertThat(requestId).isNotEqualTo("bad id");
        verify(operationalMetrics).recordHttpRequest(eq("GET"), eq("unmatched"), eq(200), anyLong());
    }
}
