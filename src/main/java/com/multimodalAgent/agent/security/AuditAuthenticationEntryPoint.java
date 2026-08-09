package com.multimodalAgent.agent.security;

import com.multimodalAgent.agent.domain.AuditAction;
import com.multimodalAgent.agent.domain.AuditOutcome;
import com.multimodalAgent.agent.domain.AuditResourceType;
import com.multimodalAgent.agent.service.audit.AuditLogService;
import com.multimodalAgent.agent.service.audit.AuditRequestMetadata;
import java.util.Map;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Records failed API authentication before returning the standard bearer 401 response. */
@Component
public class AuditAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private final AuditLogService auditLogService;
    private final BearerTokenServerAuthenticationEntryPoint delegate =
            new BearerTokenServerAuthenticationEntryPoint();

    public AuditAuthenticationEntryPoint(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException authenticationException) {
        return Mono.fromRunnable(() -> auditLogService.record(
                        null,
                        AuditAction.AUTH_ACCESS_FAILED,
                        AuditResourceType.ENDPOINT,
                        exchange.getRequest().getPath().value(),
                        AuditOutcome.FAILURE,
                        AuditRequestMetadata.from(exchange),
                        null,
                        Map.of()))
                .subscribeOn(Schedulers.boundedElastic())
                .then(delegate.commence(exchange, authenticationException));
    }
}
