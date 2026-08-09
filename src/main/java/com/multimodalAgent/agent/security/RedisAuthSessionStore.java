package com.multimodalAgent.agent.security;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
@ConditionalOnProperty(
        prefix = "multimodal-agent.security",
        name = "auth-session-store",
        havingValue = "redis",
        matchIfMissing = true)
public class RedisAuthSessionStore implements AuthSessionStore {

    private static final String SESSION_PREFIX = "auth:session:";
    private static final String USER_SESSIONS_PREFIX = "auth:user-sessions:";

    private static final DefaultRedisScript<Long> CREATE_SCRIPT = new DefaultRedisScript<>("""
            redis.call('HSET', KEYS[1],
              'userId', ARGV[1],
              'refreshTokenHash', ARGV[2],
              'expiresAt', ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            redis.call('SADD', KEYS[2], ARGV[4])
            redis.call('PEXPIRE', KEYS[2], ARGV[5])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> ROTATE_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('HGET', KEYS[1], 'refreshTokenHash')
            if not current then return 0 end
            if current ~= ARGV[1] then
              redis.call('DEL', KEYS[1])
              redis.call('SREM', KEYS[2], ARGV[4])
              return 2
            end
            redis.call('HSET', KEYS[1], 'refreshTokenHash', ARGV[2], 'expiresAt', ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> REVOKE_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('HGET', KEYS[1], 'userId')
            if not current or current ~= ARGV[1] then return 0 end
            redis.call('DEL', KEYS[1])
            redis.call('SREM', KEYS[2], ARGV[2])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> REVOKE_ALL_SCRIPT = new DefaultRedisScript<>("""
            local sessions = redis.call('SMEMBERS', KEYS[1])
            for _, sessionId in ipairs(sessions) do
              redis.call('DEL', ARGV[1] .. sessionId)
            end
            redis.call('DEL', KEYS[1])
            return #sessions
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisAuthSessionStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> create(AuthSession session) {
        return blocking(() -> {
            long ttlMillis = positiveTtl(session.expiresAt()).toMillis();
            redisTemplate.execute(
                    CREATE_SCRIPT,
                    List.of(sessionKey(session.sessionId()), userSessionsKey(session.userId())),
                    String.valueOf(session.userId()),
                    session.refreshTokenHash(),
                    String.valueOf(session.expiresAt().toEpochMilli()),
                    session.sessionId(),
                    String.valueOf(ttlMillis));
            return true;
        }).then();
    }

    @Override
    public Mono<Boolean> isActive(String sessionId, Long userId) {
        return blocking(() -> {
            Map<Object, Object> values = redisTemplate.opsForHash().entries(sessionKey(sessionId));
            if (values.isEmpty()) {
                return false;
            }
            String storedUserId = String.valueOf(values.get("userId"));
            String expiresAt = String.valueOf(values.get("expiresAt"));
            try {
                return String.valueOf(userId).equals(storedUserId)
                        && Long.parseLong(expiresAt) > Instant.now().toEpochMilli();
            } catch (NumberFormatException exception) {
                return false;
            }
        });
    }

    @Override
    public Mono<RotationResult> rotate(
            String sessionId,
            String presentedTokenHash,
            String replacementTokenHash,
            Instant replacementExpiresAt
    ) {
        return blocking(() -> {
            Object rawUserId = redisTemplate.opsForHash().get(sessionKey(sessionId), "userId");
            if (rawUserId == null) {
                return new RotationResult(RotationStatus.INVALID, null);
            }
            Long userId;
            try {
                userId = Long.valueOf(rawUserId.toString());
            } catch (NumberFormatException exception) {
                redisTemplate.delete(sessionKey(sessionId));
                return new RotationResult(RotationStatus.INVALID, null);
            }
            long ttlMillis = positiveTtl(replacementExpiresAt).toMillis();
            Long status = redisTemplate.execute(
                    ROTATE_SCRIPT,
                    List.of(sessionKey(sessionId), userSessionsKey(userId)),
                    presentedTokenHash,
                    replacementTokenHash,
                    String.valueOf(replacementExpiresAt.toEpochMilli()),
                    sessionId,
                    String.valueOf(ttlMillis));
            if (Long.valueOf(1).equals(status)) {
                redisTemplate.expire(userSessionsKey(userId), Duration.ofMillis(ttlMillis));
                return new RotationResult(
                        RotationStatus.ROTATED,
                        new AuthSession(sessionId, userId, replacementTokenHash, replacementExpiresAt));
            }
            return new RotationResult(
                    Long.valueOf(2).equals(status) ? RotationStatus.REUSED : RotationStatus.INVALID,
                    null);
        });
    }

    @Override
    public Mono<Boolean> revokeSession(String sessionId, Long userId) {
        return blocking(() -> {
            Long revoked = redisTemplate.execute(
                    REVOKE_SCRIPT,
                    List.of(sessionKey(sessionId), userSessionsKey(userId)),
                    String.valueOf(userId),
                    sessionId);
            return Long.valueOf(1).equals(revoked);
        });
    }

    @Override
    public Mono<Long> revokeAll(Long userId) {
        return blocking(() -> {
            Long revoked = redisTemplate.execute(
                    REVOKE_ALL_SCRIPT,
                    List.of(userSessionsKey(userId)),
                    SESSION_PREFIX);
            return revoked == null ? 0L : revoked;
        });
    }

    private <T> Mono<T> blocking(java.util.concurrent.Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private Duration positiveTtl(Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        return ttl.isNegative() || ttl.isZero() ? Duration.ofMillis(1) : ttl;
    }

    private String sessionKey(String sessionId) {
        return SESSION_PREFIX + sessionId;
    }

    private String userSessionsKey(Long userId) {
        return USER_SESSIONS_PREFIX + userId;
    }
}
