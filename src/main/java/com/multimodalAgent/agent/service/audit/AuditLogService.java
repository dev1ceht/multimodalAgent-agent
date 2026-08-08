package com.multimodalAgent.agent.service.audit;

import com.multimodalAgent.agent.domain.AuditAction;
import com.multimodalAgent.agent.domain.AuditLog;
import com.multimodalAgent.agent.domain.AuditOutcome;
import com.multimodalAgent.agent.domain.AuditResourceType;
import com.multimodalAgent.agent.repository.AuditLogRepository;
import com.multimodalAgent.agent.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Writes and reads bounded audit records without persisting sensitive business payloads. */
@Service
public class AuditLogService {

    private static final String ANONYMOUS = "anonymous";
    private static final String INVALID_REQUEST_ID = "invalid-request-id";
    private static final String LOCAL_RESOURCE_HASH_SECRET = "local-development-only-change-me";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern SAFE_IP_ADDRESS = Pattern.compile("[A-Za-z0-9:.%_-]{1,64}");
    private static final Set<String> NUMERIC_DETAIL_KEYS = Set.of("chunk_count", "result_count");
    private static final Set<String> ENUM_DETAIL_KEYS = Set.of("file_type", "scope", "status");
    private static final Set<String> SAFE_FILE_TYPES = Set.of("pdf", "markdown", "txt", "json", "unknown");
    private static final Set<String> SAFE_SCOPES = Set.of("self", "admin");
    private static final Set<String> SAFE_STATUSES = Set.of(
            "published", "draft", "indexing", "failed", "not_started", "unknown");

    private final AuditLogRepository auditLogRepository;
    private final byte[] resourceHashSecret;

    public AuditLogService(
            AuditLogRepository auditLogRepository,
            @Value("${multimodal-agent.audit.resource-hash-secret:local-development-only-change-me}")
            String resourceHashSecret
    ) {
        this.auditLogRepository = auditLogRepository;
        String normalizedSecret = resourceHashSecret == null || resourceHashSecret.isBlank()
                ? LOCAL_RESOURCE_HASH_SECRET
                : resourceHashSecret;
        this.resourceHashSecret = normalizedSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public AuditLog record(
            CurrentUser actor,
            AuditAction action,
            AuditResourceType resourceType,
            String resourceId,
            AuditOutcome outcome,
            AuditRequestMetadata requestMetadata,
            Long studentUserId,
            Map<String, ?> details
    ) {
        String actorUsername = actor == null || actor.getUsername() == null || actor.getUsername().isBlank()
                ? ANONYMOUS
                : bounded(actor.getUsername().trim(), 80);
        Long actorUserId = actor == null ? null : actor.getId();
        AuditLog auditLog = AuditLog.recorded(
                actorUserId,
                studentUserId,
                actorUsername,
                action,
                resourceType,
                hashResource(resourceId),
                outcome,
                action.name().toLowerCase(java.util.Locale.ROOT),
                normalizeRequestId(requestMetadata == null ? null : requestMetadata.requestId()),
                normalizeIpAddress(requestMetadata == null ? null : requestMetadata.ipAddress()),
                normalizeUserAgent(requestMetadata == null ? null : requestMetadata.userAgent()),
                serializeSafeDetails(details),
                Instant.now());
        return auditLogRepository.save(auditLog);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> latest() {
        return List.copyOf(auditLogRepository.findTop100ByOrderByCreatedAtDesc());
    }

    private String normalizeRequestId(String requestId) {
        if (requestId == null) {
            return INVALID_REQUEST_ID;
        }
        String normalized = requestId.trim();
        return SAFE_REQUEST_ID.matcher(normalized).matches() ? normalized : INVALID_REQUEST_ID;
    }

    private String normalizeIpAddress(String ipAddress) {
        if (ipAddress == null || !SAFE_IP_ADDRESS.matcher(ipAddress).matches()) {
            return "unknown";
        }
        return bounded(ipAddress, 64);
    }

    private String normalizeUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "unknown";
        }
        String normalized = userAgent.replaceAll("[\\r\\n\\t]", " ").trim();
        return bounded(normalized, 300);
    }

    private String hashResource(String resourceId) {
        String normalized = resourceId == null || resourceId.isBlank() ? "none" : resourceId;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(resourceHashSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
        }
    }

    private String serializeSafeDetails(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        details.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.nullsLast(String::compareTo)))
                .forEach(entry -> normalizeDetail(entry.getKey(), entry.getValue()).ifPresent(values::add));
        if (values.isEmpty()) {
            return null;
        }
        return bounded(String.join(";", values), 240);
    }

    private java.util.Optional<String> normalizeDetail(String key, Object value) {
        if (key == null || value == null) {
            return java.util.Optional.empty();
        }
        if (NUMERIC_DETAIL_KEYS.contains(key) && value instanceof Number number) {
            long normalized = number.longValue();
            if (normalized >= 0 && normalized <= 1_000_000 && number.doubleValue() == normalized) {
                return java.util.Optional.of(key + "=" + normalized);
            }
        }
        if (ENUM_DETAIL_KEYS.contains(key) && value instanceof String text) {
            String normalized = text.trim().toLowerCase(java.util.Locale.ROOT);
            boolean allowed = switch (key) {
                case "file_type" -> SAFE_FILE_TYPES.contains(normalized);
                case "scope" -> SAFE_SCOPES.contains(normalized);
                case "status" -> SAFE_STATUSES.contains(normalized);
                default -> false;
            };
            if (allowed) {
                return java.util.Optional.of(key + "=" + normalized);
            }
        }
        return java.util.Optional.empty();
    }

    private String bounded(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
