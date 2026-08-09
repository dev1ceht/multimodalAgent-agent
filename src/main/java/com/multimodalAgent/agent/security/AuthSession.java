package com.multimodalAgent.agent.security;

import java.time.Instant;

public record AuthSession(
        String sessionId,
        Long userId,
        String refreshTokenHash,
        Instant expiresAt
) {
}
