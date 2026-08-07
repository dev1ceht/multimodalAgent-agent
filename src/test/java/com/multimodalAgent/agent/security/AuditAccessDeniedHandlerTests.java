package com.multimodalAgent.agent.security;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

import com.multimodalAgent.agent.domain.AuditAction;
import com.multimodalAgent.agent.domain.AuditOutcome;
import com.multimodalAgent.agent.domain.AuditResourceType;
import com.multimodalAgent.agent.service.audit.AuditLogService;
import com.multimodalAgent.agent.service.audit.AuditRequestMetadata;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class AuditAccessDeniedHandlerTests {

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private AuditAccessDeniedHandler handler;

    @Test
    void recordsDeniedEndpointAndReturnsForbiddenForAnonymousPrincipal() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/admin/reports").build());

        handler.handle(exchange, new AccessDeniedException("admin role required")).block();

        verify(auditLogService).record(
                isNull(),
                eq(AuditAction.ACCESS_DENIED),
                eq(AuditResourceType.ENDPOINT),
                eq("/api/admin/reports"),
                eq(AuditOutcome.DENIED),
                eq(AuditRequestMetadata.from(exchange)),
                isNull(),
                eq(Map.of()));
        org.assertj.core.api.Assertions.assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unwrapsCurrentUserFromReactiveAuthenticationPrincipal() {
        CurrentUser actor = currentUser("student");
        var authentication = new UsernamePasswordAuthenticationToken(
                actor, actor.getPassword(), actor.getAuthorities());
        var exchange = MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/admin/reports").build())
                .mutate()
                .principal(Mono.just(authentication))
                .build();

        handler.handle(exchange, new AccessDeniedException("admin role required")).block();

        verify(auditLogService).record(
                eq(actor),
                eq(AuditAction.ACCESS_DENIED),
                eq(AuditResourceType.ENDPOINT),
                eq("/api/admin/reports"),
                eq(AuditOutcome.DENIED),
                eq(AuditRequestMetadata.from(exchange)),
                isNull(),
                eq(Map.of()));
    }

    private CurrentUser currentUser(String username) {
        com.multimodalAgent.agent.domain.UserAccount account =
                new com.multimodalAgent.agent.domain.UserAccount();
        account.setUsername(username);
        account.setPassword("hashed-password");
        account.setDisplayName(username);
        account.setRoles(java.util.Set.of("ROLE_USER"));
        return new CurrentUser(account);
    }
}
