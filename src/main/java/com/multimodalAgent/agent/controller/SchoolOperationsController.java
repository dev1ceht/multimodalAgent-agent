package com.multimodalAgent.agent.controller;

import com.multimodalAgent.agent.domain.AuditAction;
import com.multimodalAgent.agent.domain.AuditOutcome;
import com.multimodalAgent.agent.domain.AuditResourceType;
import com.multimodalAgent.agent.dto.OperationsOverviewResponse;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.service.OperationsOverviewService;
import com.multimodalAgent.agent.service.OperationsWindow;
import com.multimodalAgent.agent.service.audit.AuditLogService;
import com.multimodalAgent.agent.service.audit.AuditRequestMetadata;
import java.time.Instant;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

@RestController
@RequestMapping("/api/admin/operations")
public class SchoolOperationsController {

    private final OperationsOverviewService operationsOverviewService;
    private final AuditLogService auditLogService;

    public SchoolOperationsController(
            OperationsOverviewService operationsOverviewService,
            AuditLogService auditLogService
    ) {
        this.operationsOverviewService = operationsOverviewService;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/overview")
    public OperationsOverviewResponse overview(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        try {
            OperationsOverviewResponse response = operationsOverviewService.overview(currentUser, from, to);
            auditLogService.record(
                    currentUser,
                    AuditAction.OPERATIONS_OVERVIEW_VIEW,
                    AuditResourceType.OPERATIONS,
                    "overview",
                    AuditOutcome.SUCCESS,
                    AuditRequestMetadata.from(exchange),
                    null,
                    Map.of(
                            "scope", "operations",
                            "window_from", response.from(),
                            "window_to", response.to()));
            return response;
        } catch (ResponseStatusException exception) {
            auditLogService.record(
                    currentUser,
                    AuditAction.OPERATIONS_OVERVIEW_VIEW,
                    AuditResourceType.OPERATIONS,
                    "overview",
                    AuditOutcome.DENIED,
                    AuditRequestMetadata.from(exchange),
                    null,
                    deniedDetails(from, to));
            throw exception;
        }
    }

    private Map<String, ?> deniedDetails(Instant from, Instant to) {
        try {
            OperationsWindow window = operationsOverviewService.normalizeWindow(from, to);
            return Map.of(
                    "scope", "operations",
                    "window_from", window.from(),
                    "window_to", window.to());
        } catch (ResponseStatusException ignored) {
            return Map.of("scope", "operations");
        }
    }
}
