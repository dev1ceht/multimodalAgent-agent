package com.multimodalAgent.agent.controller;

import com.multimodalAgent.agent.domain.AuditAction;
import com.multimodalAgent.agent.domain.AuditOutcome;
import com.multimodalAgent.agent.domain.AuditResourceType;
import com.multimodalAgent.agent.dto.ConsentGrantRequest;
import com.multimodalAgent.agent.dto.ConsentResponse;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.service.ConsentService;
import com.multimodalAgent.agent.service.audit.AuditLogService;
import com.multimodalAgent.agent.service.audit.AuditRequestMetadata;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

@RestController
@RequestMapping("/api/student/consents")
public class ConsentController {

    private final ConsentService consentService;
    private final AuditLogService auditLogService;

    public ConsentController(ConsentService consentService, AuditLogService auditLogService) {
        this.consentService = consentService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public List<ConsentResponse> list(
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        try {
            List<ConsentResponse> consents = consentService.list(currentUser).stream()
                    .map(ConsentResponse::from)
                    .toList();
            auditLogService.record(
                    currentUser,
                    AuditAction.CONSENT_LIST_VIEW,
                    AuditResourceType.CONSENT,
                    "self",
                    AuditOutcome.SUCCESS,
                    AuditRequestMetadata.from(exchange),
                    currentUser.getId(),
                    Map.of("scope", "self", "result_count", consents.size()));
            return consents;
        } catch (ResponseStatusException exception) {
            recordDenied(currentUser, AuditAction.CONSENT_LIST_VIEW, exchange);
            throw exception;
        }
    }

    @PostMapping
    public ConsentResponse grant(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody ConsentGrantRequest request,
            ServerWebExchange exchange
    ) {
        try {
            ConsentResponse response = ConsentResponse.from(consentService.grant(currentUser, request));
            auditLogService.record(
                    currentUser,
                    AuditAction.CONSENT_GRANT,
                    AuditResourceType.CONSENT,
                    request.consentType().name() + ":" + request.version(),
                    AuditOutcome.SUCCESS,
                    AuditRequestMetadata.from(exchange),
                    currentUser.getId(),
                    Map.of("status", "granted"));
            return response;
        } catch (ResponseStatusException exception) {
            recordDenied(currentUser, AuditAction.CONSENT_GRANT, exchange);
            throw exception;
        }
    }

    private void recordDenied(
            CurrentUser currentUser,
            AuditAction action,
            ServerWebExchange exchange
    ) {
        auditLogService.record(
                currentUser,
                action,
                AuditResourceType.CONSENT,
                "self",
                AuditOutcome.DENIED,
                AuditRequestMetadata.from(exchange),
                currentUser == null ? null : currentUser.getId(),
                Map.of());
    }
}
