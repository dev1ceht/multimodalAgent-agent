package com.multimodalAgent.agent.controller;

import com.multimodalAgent.agent.domain.AuditAction;
import com.multimodalAgent.agent.domain.AuditOutcome;
import com.multimodalAgent.agent.domain.AuditResourceType;
import com.multimodalAgent.agent.dto.StudentProfileResponse;
import com.multimodalAgent.agent.dto.StudentProfileUpdateRequest;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.service.StudentProfileService;
import com.multimodalAgent.agent.service.audit.AuditLogService;
import com.multimodalAgent.agent.service.audit.AuditRequestMetadata;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

@RestController
@RequestMapping("/api/student/profile")
public class StudentProfileController {

    private final StudentProfileService studentProfileService;
    private final AuditLogService auditLogService;

    public StudentProfileController(
            StudentProfileService studentProfileService,
            AuditLogService auditLogService
    ) {
        this.studentProfileService = studentProfileService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public StudentProfileResponse get(
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        try {
            StudentProfileResponse response = StudentProfileResponse.from(studentProfileService.get(currentUser));
            auditLogService.record(
                    currentUser,
                    AuditAction.PROFILE_VIEW,
                    AuditResourceType.PROFILE,
                    "self",
                    AuditOutcome.SUCCESS,
                    AuditRequestMetadata.from(exchange),
                    currentUser.getId(),
                    Map.of("scope", "self"));
            return response;
        } catch (ResponseStatusException exception) {
            recordDenied(currentUser, AuditAction.PROFILE_VIEW, exchange);
            throw exception;
        }
    }

    @PutMapping
    public StudentProfileResponse update(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody StudentProfileUpdateRequest request,
            ServerWebExchange exchange
    ) {
        try {
            StudentProfileResponse response = StudentProfileResponse.from(
                    studentProfileService.update(currentUser, request));
            auditLogService.record(
                    currentUser,
                    AuditAction.PROFILE_UPDATE,
                    AuditResourceType.PROFILE,
                    "self",
                    AuditOutcome.SUCCESS,
                    AuditRequestMetadata.from(exchange),
                    currentUser.getId(),
                    Map.of("scope", "self"));
            return response;
        } catch (ResponseStatusException exception) {
            recordDenied(currentUser, AuditAction.PROFILE_UPDATE, exchange);
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
                AuditResourceType.PROFILE,
                "self",
                AuditOutcome.DENIED,
                AuditRequestMetadata.from(exchange),
                currentUser == null ? null : currentUser.getId(),
                Map.of());
    }
}
