package com.multimodalAgent.agent.security;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(
        prefix = "multimodal-agent.security",
        name = "auth-session-store",
        havingValue = "memory")
public class InMemoryAuthSessionStore implements AuthSessionStore {

    private final ConcurrentHashMap<String, AuthSession> sessions = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> create(AuthSession session) {
        sessions.put(session.sessionId(), session);
        return Mono.empty();
    }

    @Override
    public Mono<Boolean> isActive(String sessionId, Long userId) {
        AuthSession session = sessions.get(sessionId);
        boolean active = session != null
                && session.userId().equals(userId)
                && session.expiresAt().isAfter(Instant.now());
        return Mono.just(active);
    }

    @Override
    public Mono<RotationResult> rotate(
            String sessionId,
            String presentedTokenHash,
            String replacementTokenHash,
            Instant replacementExpiresAt
    ) {
        RotationResult result;
        synchronized (sessions) {
            AuthSession current = sessions.get(sessionId);
            if (current == null || !current.expiresAt().isAfter(Instant.now())) {
                sessions.remove(sessionId);
                result = new RotationResult(RotationStatus.INVALID, null);
            } else if (!current.refreshTokenHash().equals(presentedTokenHash)) {
                sessions.remove(sessionId);
                result = new RotationResult(RotationStatus.REUSED, null);
            } else {
                AuthSession replacement = new AuthSession(
                        current.sessionId(),
                        current.userId(),
                        replacementTokenHash,
                        replacementExpiresAt);
                sessions.put(sessionId, replacement);
                result = new RotationResult(RotationStatus.ROTATED, replacement);
            }
        }
        return Mono.just(result);
    }

    @Override
    public Mono<Boolean> revokeSession(String sessionId, Long userId) {
        boolean revoked = false;
        synchronized (sessions) {
            AuthSession current = sessions.get(sessionId);
            if (current != null && current.userId().equals(userId)) {
                sessions.remove(sessionId);
                revoked = true;
            }
        }
        return Mono.just(revoked);
    }

    @Override
    public Mono<Long> revokeAll(Long userId) {
        long removed;
        synchronized (sessions) {
            long before = sessions.size();
            sessions.entrySet().removeIf(entry -> entry.getValue().userId().equals(userId));
            removed = before - sessions.size();
        }
        return Mono.just(removed);
    }
}
