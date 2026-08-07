package com.multimodalAgent.agent.security;

import com.multimodalAgent.agent.domain.AuditAction;
import com.multimodalAgent.agent.domain.AuditOutcome;
import com.multimodalAgent.agent.domain.AuditResourceType;
import com.multimodalAgent.agent.service.audit.AuditLogService;
import com.multimodalAgent.agent.service.audit.AuditRequestMetadata;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Records authenticated authorization failures before returning a stable 403 response. */
@Component
public class AuditAccessDeniedHandler implements ServerAccessDeniedHandler {

    private final AuditLogService auditLogService;

    public AuditAccessDeniedHandler(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        return exchange.getPrincipal()
                .map(this::resolveCurrentUser)
                .flatMap(currentUser -> recordAndRespond(exchange, currentUser))
                .switchIfEmpty(Mono.defer(() -> recordAndRespond(exchange, null)));
    }

    private CurrentUser resolveCurrentUser(Principal principal) {
        if (principal instanceof CurrentUser currentUser) {
            return currentUser;
        }
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof CurrentUser currentUser) {
            return currentUser;
        }
        return null;
    }

    private Mono<Void> recordAndRespond(ServerWebExchange exchange, CurrentUser currentUser) {
        return Mono.fromRunnable(() -> auditLogService.record(
                        currentUser,
                        AuditAction.ACCESS_DENIED,
                        AuditResourceType.ENDPOINT,
                        exchange.getRequest().getPath().value(),
                        AuditOutcome.DENIED,
                        AuditRequestMetadata.from(exchange),
                        null,
                        Map.of()))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.defer(() -> {
                    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                    return exchange.getResponse().setComplete();
                }));
    }
}
