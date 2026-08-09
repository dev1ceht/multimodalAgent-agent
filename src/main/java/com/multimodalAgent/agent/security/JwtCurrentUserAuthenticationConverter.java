package com.multimodalAgent.agent.security;

import java.util.Objects;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class JwtCurrentUserAuthenticationConverter
        implements Converter<Jwt, Mono<AbstractAuthenticationToken>> {

    private final AuthSessionStore authSessionStore;
    private final CurrentUserDetailsService userDetailsService;

    public JwtCurrentUserAuthenticationConverter(
            AuthSessionStore authSessionStore,
            CurrentUserDetailsService userDetailsService
    ) {
        this.authSessionStore = authSessionStore;
        this.userDetailsService = userDetailsService;
    }

    @Override
    public Mono<AbstractAuthenticationToken> convert(Jwt jwt) {
        if (!AccessTokenService.ACCESS_TOKEN_TYPE.equals(jwt.getClaimAsString(AccessTokenService.TOKEN_TYPE_CLAIM))) {
            return Mono.error(new BadCredentialsException("Unsupported token type"));
        }
        Long userId;
        try {
            userId = Long.valueOf(jwt.getSubject());
        } catch (RuntimeException exception) {
            return Mono.error(new BadCredentialsException("Invalid token subject", exception));
        }
        String sessionId = jwt.getClaimAsString(AccessTokenService.SESSION_ID_CLAIM);
        if (sessionId == null || sessionId.isBlank()) {
            return Mono.error(new BadCredentialsException("Missing token session"));
        }
        return authSessionStore.isActive(sessionId, userId)
                .filter(Boolean::booleanValue)
                .switchIfEmpty(Mono.error(new BadCredentialsException("Session is not active")))
                .then(userDetailsService.findById(userId))
                .filter(CurrentUser::isEnabled)
                .switchIfEmpty(Mono.error(new BadCredentialsException("Account is disabled")))
                .map(user -> {
                    var authentication = new UsernamePasswordAuthenticationToken(
                            user,
                            Objects.requireNonNullElse(jwt.getTokenValue(), ""),
                            user.getAuthorities());
                    authentication.setDetails(new AuthenticatedSession(sessionId));
                    return authentication;
                });
    }

    public record AuthenticatedSession(String sessionId) {
    }
}
