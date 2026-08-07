package com.multimodalAgent.agent.service.audit;

import com.multimodalAgent.agent.service.observability.RequestCorrelationWebFilter;
import java.net.InetSocketAddress;
import org.springframework.web.server.ServerWebExchange;

/** Bounded request context captured for audit correlation and incident investigation. */
public record AuditRequestMetadata(
        String requestId,
        String ipAddress,
        String userAgent
) {

    public static AuditRequestMetadata from(ServerWebExchange exchange) {
        if (exchange == null) {
            return new AuditRequestMetadata(null, null, null);
        }
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        String ipAddress = remoteAddress == null || remoteAddress.getAddress() == null
                ? null
                : remoteAddress.getAddress().getHostAddress();
        return new AuditRequestMetadata(
                exchange.getResponse().getHeaders().getFirst(RequestCorrelationWebFilter.REQUEST_ID_HEADER),
                ipAddress,
                exchange.getRequest().getHeaders().getFirst("User-Agent"));
    }
}
