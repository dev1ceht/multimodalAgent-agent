package com.multimodalAgent.agent.service.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.multimodalAgent.agent.domain.AuditAction;
import com.multimodalAgent.agent.domain.AuditLog;
import com.multimodalAgent.agent.domain.AuditOutcome;
import com.multimodalAgent.agent.domain.AuditResourceType;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.repository.AuditLogRepository;
import com.multimodalAgent.agent.security.CurrentUser;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTests {

    @Mock
    private AuditLogRepository auditLogRepository;

    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        auditLogService = new AuditLogService(auditLogRepository, "test-audit-secret");
    }

    @Test
    void recordsActorOutcomeRequestAndHashedResourceWithoutRawDetails() {
        CurrentUser actor = currentUser("admin");

        auditLogService.record(
                actor,
                AuditAction.KNOWLEDGE_INGEST,
                AuditResourceType.KNOWLEDGE,
                "student/session-123",
                AuditOutcome.SUCCESS,
                new AuditRequestMetadata("req-42", "10.0.0.4", "JUnit"),
                77L,
                Map.of("chunk_count", 12, "raw_content", "student disclosed sensitive content"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();

        assertThat(saved.getActorUsername()).isEqualTo("admin");
        assertThat(saved.getAction()).isEqualTo(AuditAction.KNOWLEDGE_INGEST);
        assertThat(saved.getResourceType()).isEqualTo(AuditResourceType.KNOWLEDGE);
        assertThat(saved.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(saved.getReason()).isEqualTo("knowledge_ingest");
        assertThat(saved.getRequestId()).isEqualTo("req-42");
        assertThat(saved.getStudentUserId()).isEqualTo(77L);
        assertThat(saved.getIpAddress()).isEqualTo("10.0.0.4");
        assertThat(saved.getUserAgent()).isEqualTo("JUnit");
        assertThat(saved.getResourceRef())
                .hasSize(64)
                .doesNotContain("student/session-123");
        assertThat(saved.getDetails())
                .isEqualTo("chunk_count=12")
                .doesNotContain("sensitive");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void boundsUntrustedRequestIdentifiersAndSupportsAnonymousActors() {
        auditLogService.record(
                null,
                AuditAction.ACCESS_DENIED,
                AuditResourceType.ENDPOINT,
                "/api/admin/reports?student=secret",
                AuditOutcome.DENIED,
                new AuditRequestMetadata(
                        "bad request id with spaces and secrets",
                        "not an ip",
                        "agent\r\nforged"),
                null,
                Map.of("scope", "admin"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();

        assertThat(saved.getActorUsername()).isEqualTo("anonymous");
        assertThat(saved.getRequestId()).isEqualTo("invalid-request-id");
        assertThat(saved.getIpAddress()).isEqualTo("unknown");
        assertThat(saved.getUserAgent()).isEqualTo("agent  forged");
        assertThat(saved.getDetails()).isEqualTo("scope=admin");
        assertThat(saved.getReason()).isEqualTo("access_denied");
    }

    private CurrentUser currentUser(String username) {
        UserAccount account = new UserAccount();
        account.setUsername(username);
        account.setPassword("hashed-password");
        account.setDisplayName("Administrator");
        account.setRoles(java.util.Set.of("ROLE_ADMIN"));
        return new CurrentUser(account);
    }
}
