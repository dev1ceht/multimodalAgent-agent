package com.multimodalAgent.agent.controller;

import com.multimodalAgent.agent.dto.LoginRequest;
import com.multimodalAgent.agent.dto.LoginResponse;
import com.multimodalAgent.agent.dto.CurrentAccountResponse;
import com.multimodalAgent.agent.security.AuthenticationService;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.domain.AuditAction;
import com.multimodalAgent.agent.domain.AuditOutcome;
import com.multimodalAgent.agent.domain.AuditResourceType;
import com.multimodalAgent.agent.service.audit.AuditLogService;
import com.multimodalAgent.agent.service.audit.AuditRequestMetadata;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/auth")
public class AuthenticationController {

    private final AuthenticationService authenticationService;
    private final multimodalAgentProperties properties;
    private final AuditLogService auditLogService;

    public AuthenticationController(
            AuthenticationService authenticationService,
            multimodalAgentProperties properties,
            AuditLogService auditLogService
    ) {
        this.authenticationService = authenticationService;
        this.properties = properties;
        this.auditLogService = auditLogService;
    }

    @PostMapping("/login")
    public Mono<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            ServerHttpResponse response,
            ServerWebExchange exchange
    ) {
        return authenticationService.login(request)
                .doOnSuccess(result -> record(
                        result.user(), AuditAction.AUTH_LOGIN, AuditResourceType.SESSION,
                        request.username(), AuditOutcome.SUCCESS, exchange))
                .doOnError(error -> record(
                        null, AuditAction.AUTH_LOGIN_FAILED, AuditResourceType.ACCOUNT,
                        request.username(), AuditOutcome.FAILURE, exchange))
                .map(result -> writeRefreshCookie(response, result));
    }

    @PostMapping("/refresh")
    public Mono<LoginResponse> refresh(
            @CookieValue(name = "refresh_token", required = false) String refreshToken,
            ServerHttpResponse response,
            ServerWebExchange exchange
    ) {
        return authenticationService.refresh(refreshToken)
                .doOnSuccess(result -> record(
                        result.user(), AuditAction.AUTH_REFRESH, AuditResourceType.SESSION,
                        "refresh", AuditOutcome.SUCCESS, exchange))
                .doOnError(error -> record(
                        null, AuditAction.AUTH_REFRESH_FAILED, AuditResourceType.SESSION,
                        "refresh", AuditOutcome.FAILURE, exchange))
                .map(result -> writeRefreshCookie(response, result));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> logout(
            @AuthenticationPrincipal CurrentUser currentUser,
            Authentication authentication,
            ServerHttpResponse response,
            ServerWebExchange exchange
    ) {
        clearRefreshCookie(response);
        var session = (com.multimodalAgent.agent.security.JwtCurrentUserAuthenticationConverter.AuthenticatedSession)
                authentication.getDetails();
        return authenticationService.logout(session.sessionId(), currentUser.getId())
                .doOnSuccess(ignored -> record(
                        currentUser, AuditAction.AUTH_LOGOUT, AuditResourceType.SESSION,
                        "logout", AuditOutcome.SUCCESS, exchange));
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> logoutAll(
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerHttpResponse response,
            ServerWebExchange exchange
    ) {
        clearRefreshCookie(response);
        return authenticationService.logoutAll(currentUser.getId())
                .doOnSuccess(ignored -> record(
                        currentUser, AuditAction.AUTH_LOGOUT_ALL, AuditResourceType.ACCOUNT,
                        "logout-all", AuditOutcome.SUCCESS, exchange));
    }

    @GetMapping("/me")
    public CurrentAccountResponse me(@AuthenticationPrincipal CurrentUser currentUser) {
        return CurrentAccountResponse.from(currentUser);
    }

    private LoginResponse writeRefreshCookie(
            ServerHttpResponse response,
            AuthenticationService.AuthenticationResult result
    ) {
        Duration maxAge = Duration.between(java.time.Instant.now(), result.refreshExpiresAt());
        response.addCookie(ResponseCookie.from("refresh_token", result.refreshToken())
                .httpOnly(true)
                .secure(properties.getSecurity().isRefreshCookieSecure())
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
                .build());
        return result.response();
    }

    private void clearRefreshCookie(ServerHttpResponse response) {
        response.addCookie(ResponseCookie.from("refresh_token", "")
                .httpOnly(true)
                .secure(properties.getSecurity().isRefreshCookieSecure())
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(Duration.ZERO)
                .build());
    }

    private void record(
            CurrentUser actor,
            AuditAction action,
            AuditResourceType resourceType,
            String resourceId,
            AuditOutcome outcome,
            ServerWebExchange exchange
    ) {
        auditLogService.record(
                actor,
                action,
                resourceType,
                resourceId,
                outcome,
                AuditRequestMetadata.from(exchange),
                null,
                java.util.Map.of());
    }
}
