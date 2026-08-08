package com.multimodalAgent.agent.controller;

import com.multimodalAgent.agent.domain.AuditAction;
import com.multimodalAgent.agent.domain.AuditOutcome;
import com.multimodalAgent.agent.domain.AuditResourceType;
import com.multimodalAgent.agent.dto.StudentSupportStatusResponse;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.service.RiskCaseService;
import com.multimodalAgent.agent.service.audit.AuditLogService;
import com.multimodalAgent.agent.service.audit.AuditRequestMetadata;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

@RestController
@RequestMapping("/api/student/support-status")
public class StudentSupportStatusController {

    private final RiskCaseService riskCaseService;
    private final AuditLogService auditLogService;

    public StudentSupportStatusController(RiskCaseService riskCaseService, AuditLogService auditLogService) {
        this.riskCaseService = riskCaseService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public List<StudentSupportStatusResponse> status(
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        try {
            List<StudentSupportStatusResponse> statuses = riskCaseService.studentSupportStatus(currentUser);
            auditLogService.record(
                    currentUser,
                    AuditAction.RISK_CASE_SELF_VIEW,
                    AuditResourceType.RISK_CASE,
                    "self",
                    AuditOutcome.SUCCESS,
                    AuditRequestMetadata.from(exchange),
                    currentUser == null ? null : currentUser.getId(),
                    Map.of("scope", "self", "result_count", statuses.size()));
            return statuses;
        } catch (ResponseStatusException exception) {
            auditLogService.record(
                    currentUser,
                    AuditAction.RISK_CASE_SELF_VIEW,
                    AuditResourceType.RISK_CASE,
                    "self",
                    AuditOutcome.DENIED,
                    AuditRequestMetadata.from(exchange),
                    null,
                    Map.of());
            throw exception;
        }
    }
}
