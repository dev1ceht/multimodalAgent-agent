package com.multimodalAgent.agent.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Service;

@Service
public class RefreshTokenService {

    private static final int SECRET_BYTES = 32;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshToken create(String sessionId) {
        byte[] secret = new byte[SECRET_BYTES];
        secureRandom.nextBytes(secret);
        String encodedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String value = sessionId + "." + encodedSecret;
        return new RefreshToken(sessionId, value, sha256(value));
    }

    public RefreshToken parse(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidRefreshTokenException();
        }
        int separator = value.indexOf('.');
        if (separator <= 0 || separator == value.length() - 1 || value.indexOf('.', separator + 1) >= 0) {
            throw new InvalidRefreshTokenException();
        }
        String sessionId = value.substring(0, separator);
        return new RefreshToken(sessionId, value, sha256(value));
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record RefreshToken(String sessionId, String value, String hash) {
    }

    public static class InvalidRefreshTokenException extends RuntimeException {
        public InvalidRefreshTokenException() {
            super("Invalid refresh token");
        }
    }
}
