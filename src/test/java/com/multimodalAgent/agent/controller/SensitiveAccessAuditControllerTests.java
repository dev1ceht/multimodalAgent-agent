package com.multimodalAgent.agent.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.domain.AuditAction;
import com.multimodalAgent.agent.domain.AuditOutcome;
import com.multimodalAgent.agent.domain.AuditResourceType;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.dto.KnowledgeIngestRequest;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.service.ReportService;
import com.multimodalAgent.agent.service.audit.AuditLogService;
import com.multimodalAgent.agent.service.audit.AuditRequestMetadata;
import com.multimodalAgent.agent.service.observability.RequestCorrelationWebFilter;
import com.multimodalAgent.agent.service.knowledge.KnowledgeFileService;
import com.multimodalAgent.agent.service.knowledge.KnowledgeService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

@ExtendWith(MockitoExtension.class)
class SensitiveAccessAuditControllerTests {

    @Mock
    private KnowledgeService knowledgeService;

    @Mock
    private KnowledgeFileService knowledgeFileService;

    @Mock
    private ReportService reportService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private KnowledgeController knowledgeController;

    @InjectMocks
    private ReportController reportController;

    @Test
    void auditsKnowledgeMutationWithRequestCorrelationAndChunkCount() {
        CurrentUser actor = currentUser("admin");
        var exchange = exchangeWithRequestId("req-42");
        when(knowledgeService.ingest("handbook.md", "safe content")).thenReturn(3);

        knowledgeController.ingest(
                new KnowledgeIngestRequest("handbook.md", "safe content"), actor, exchange);

        verify(auditLogService).record(
                eq(actor),
                eq(AuditAction.KNOWLEDGE_INGEST),
                eq(AuditResourceType.KNOWLEDGE),
                eq("handbook.md"),
                eq(AuditOutcome.SUCCESS),
                eq(AuditRequestMetadata.from(exchange)),
                eq(null),
                eq(Map.of("chunk_count", 3)));
    }

    @Test
    void auditsStudentReportViewWithSelfScopeAndResultCount() {
        CurrentUser actor = currentUser("student");
        var exchange = exchangeWithRequestId("req-report");
        when(reportService.myReports(actor.getId())).thenReturn(List.of());

        reportController.myReports(actor, exchange);

        verify(auditLogService).record(
                eq(actor),
                eq(AuditAction.REPORT_SELF_VIEW),
                eq(AuditResourceType.REPORT),
                eq("self"),
                eq(AuditOutcome.SUCCESS),
                eq(AuditRequestMetadata.from(exchange)),
                eq(actor.getId()),
                eq(Map.of("scope", "self", "result_count", 0)));
    }

    private MockServerWebExchange exchangeWithRequestId(String requestId) {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api").build());
        exchange.getResponse().getHeaders().set(RequestCorrelationWebFilter.REQUEST_ID_HEADER, requestId);
        return exchange;
    }

    private CurrentUser currentUser(String username) {
        UserAccount account = new UserAccount();
        account.setUsername(username);
        account.setPassword("hashed-password");
        account.setDisplayName(username);
        account.setRoles(java.util.Set.of("ROLE_USER"));
        return new CurrentUser(account);
    }
}
