package com.multimodalAgent.agent.security;

import reactor.core.publisher.Mono;

public interface AuthSessionStore {

    Mono<Void> create(AuthSession session);

    Mono<Boolean> isActive(String sessionId, Long userId);

    Mono<RotationResult> rotate(
            String sessionId,
            String presentedTokenHash,
            String replacementTokenHash,
            java.time.Instant replacementExpiresAt);

    Mono<Boolean> revokeSession(String sessionId, Long userId);

    Mono<Long> revokeAll(Long userId);

    record RotationResult(RotationStatus status, AuthSession session) {
    }

    enum RotationStatus {
        ROTATED,
        INVALID,
        REUSED
    }
}
