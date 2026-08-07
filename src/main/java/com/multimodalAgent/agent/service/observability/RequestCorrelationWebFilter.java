package com.multimodalAgent.agent.service.observability;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/** Adds a bounded request identifier and records normalized HTTP request timing. */
@Component
public class RequestCorrelationWebFilter implements WebFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_CONTEXT_KEY = "multimodalagent.requestId";

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final OperationalMetrics operationalMetrics;

    public RequestCorrelationWebFilter(OperationalMetrics operationalMetrics) {
        this.operationalMetrics = operationalMetrics;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = resolveRequestId(exchange);
        exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);
        String method = exchange.getRequest().getMethod() == null
                ? "unknown"
                : exchange.getRequest().getMethod().name();
        long started = System.nanoTime();
        return chain.filter(exchange)
                .doFinally(signal -> operationalMetrics.recordHttpRequest(
                        method,
                        routePattern(exchange),
                        responseStatus(exchange, signal),
                        System.nanoTime() - started))
                .contextWrite(context -> context.put(REQUEST_ID_CONTEXT_KEY, requestId));
    }

    private String resolveRequestId(ServerWebExchange exchange) {
        String requested = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        if (requested != null) {
            String normalized = requested.trim();
            if (SAFE_REQUEST_ID.matcher(normalized).matches()) {
                return normalized;
            }
        }
        return UUID.randomUUID().toString();
    }

    private String routePattern(ServerWebExchange exchange) {
        String route = exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return route == null || route.isBlank() ? "unmatched" : route;
    }

    private int responseStatus(ServerWebExchange exchange, SignalType signal) {
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        if (status != null) {
            return status.value();
        }
        if (signal == SignalType.ON_ERROR) {
            return 500;
        }
        if (signal == SignalType.CANCEL) {
            return 499;
        }
        return 200;
    }
}
