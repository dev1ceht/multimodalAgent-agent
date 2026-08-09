package com.multimodalAgent.agent.security;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.dto.LoginRequest;
import com.multimodalAgent.agent.dto.LoginResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
public class AuthenticationService {

    private final ReactiveAuthenticationManager authenticationManager;
    private final AuthSessionStore authSessionStore;
    private final AccessTokenService accessTokenService;
    private final RefreshTokenService refreshTokenService;
    private final CurrentUserDetailsService userDetailsService;
    private final multimodalAgentProperties properties;

    public AuthenticationService(
            ReactiveAuthenticationManager authenticationManager,
            AuthSessionStore authSessionStore,
            AccessTokenService accessTokenService,
            RefreshTokenService refreshTokenService,
            CurrentUserDetailsService userDetailsService,
            multimodalAgentProperties properties
    ) {
        this.authenticationManager = authenticationManager;
        this.authSessionStore = authSessionStore;
        this.accessTokenService = accessTokenService;
        this.refreshTokenService = refreshTokenService;
        this.userDetailsService = userDetailsService;
        this.properties = properties;
    }

    public Mono<AuthenticationResult> login(LoginRequest request) {
        var credentials = UsernamePasswordAuthenticationToken.unauthenticated(
                request.username().trim(),
                request.password());
        return authenticationManager.authenticate(credentials)
                .map(authentication -> (CurrentUser) authentication.getPrincipal())
                .flatMap(this::createLogin)
                .onErrorMap(AuthenticationException.class,
                        exception -> new ResponseStatusException(
                                HttpStatus.UNAUTHORIZED,
                                "Invalid username or password"));
    }

    public Mono<AuthenticationResult> refresh(String refreshTokenValue) {
        RefreshTokenService.RefreshToken presented;
        try {
            presented = refreshTokenService.parse(refreshTokenValue);
        } catch (RefreshTokenService.InvalidRefreshTokenException exception) {
            return Mono.error(unauthorized());
        }
        RefreshTokenService.RefreshToken replacement = refreshTokenService.create(presented.sessionId());
        Instant replacementExpiresAt = Instant.now().plus(properties.getSecurity().getRefreshTokenTtl());
        return authSessionStore.rotate(
                        presented.sessionId(),
                        presented.hash(),
                        replacement.hash(),
                        replacementExpiresAt)
                .flatMap(rotation -> {
                    if (rotation.status() != AuthSessionStore.RotationStatus.ROTATED
                            || rotation.session() == null) {
                        return Mono.error(unauthorized());
                    }
                    return userDetailsService.findById(rotation.session().userId());
                })
                .filter(CurrentUser::isEnabled)
                .switchIfEmpty(Mono.error(unauthorized()))
                .map(user -> issueResult(user, presented.sessionId(), replacement, replacementExpiresAt))
                .onErrorMap(AuthenticationException.class, exception -> unauthorized());
    }

    public Mono<Void> logout(String sessionId, Long userId) {
        return authSessionStore.revokeSession(sessionId, userId).then();
    }

    public Mono<Void> logoutAll(Long userId) {
        return authSessionStore.revokeAll(userId).then();
    }

    private Mono<AuthenticationResult> createLogin(CurrentUser user) {
        String sessionId = UUID.randomUUID().toString();
        Instant sessionExpiresAt = Instant.now().plus(properties.getSecurity().getRefreshTokenTtl());
        RefreshTokenService.RefreshToken refreshToken = refreshTokenService.create(sessionId);
        return authSessionStore.create(new AuthSession(
                        sessionId,
                        user.getId(),
                        refreshToken.hash(),
                        sessionExpiresAt))
                .thenReturn(issueResult(user, sessionId, refreshToken, sessionExpiresAt));
    }

    private AuthenticationResult issueResult(
            CurrentUser user,
            String sessionId,
            RefreshTokenService.RefreshToken refreshToken,
            Instant refreshExpiresAt
    ) {
        AccessTokenService.IssuedAccessToken accessToken = accessTokenService.issue(user, sessionId);
        return new AuthenticationResult(
                new LoginResponse(accessToken.value(), "Bearer", accessToken.expiresAt()),
                refreshToken.value(),
                refreshExpiresAt,
                user);
    }

    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
    }

    public record AuthenticationResult(
            LoginResponse response,
            String refreshToken,
            Instant refreshExpiresAt,
            CurrentUser user
    ) {
    }
}
