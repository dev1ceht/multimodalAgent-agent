package com.multimodalAgent.agent.controller;

import com.multimodalAgent.agent.domain.AuditAction;
import com.multimodalAgent.agent.domain.AuditOutcome;
import com.multimodalAgent.agent.domain.AuditResourceType;
import com.multimodalAgent.agent.domain.Referral;
import com.multimodalAgent.agent.domain.RiskCase;
import com.multimodalAgent.agent.dto.InterventionCreateRequest;
import com.multimodalAgent.agent.dto.InterventionResponse;
import com.multimodalAgent.agent.dto.ReferralCreateRequest;
import com.multimodalAgent.agent.dto.ReferralResponse;
import com.multimodalAgent.agent.dto.ReferralStatusRequest;
import com.multimodalAgent.agent.dto.RiskCaseResponse;
import com.multimodalAgent.agent.dto.RiskCaseStatusRequest;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.service.RiskCaseService;
import com.multimodalAgent.agent.service.audit.AuditLogService;
import com.multimodalAgent.agent.service.audit.AuditRequestMetadata;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

@RestController
@RequestMapping("/api/admin/risk-cases")
public class RiskCaseController {

    private final RiskCaseService riskCaseService;
    private final AuditLogService auditLogService;

    public RiskCaseController(RiskCaseService riskCaseService, AuditLogService auditLogService) {
        this.riskCaseService = riskCaseService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public List<RiskCaseResponse> list(
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        try {
            List<RiskCaseResponse> cases = riskCaseService.staffCases(currentUser).stream()
                    .map(RiskCaseResponse::from)
                    .toList();
            auditLogService.record(
                    currentUser,
                    AuditAction.RISK_CASE_LIST_VIEW,
                    AuditResourceType.RISK_CASE,
                    "latest",
                    AuditOutcome.SUCCESS,
                    AuditRequestMetadata.from(exchange),
                    null,
                    Map.of("scope", "admin", "result_count", cases.size()));
            return cases;
        } catch (ResponseStatusException exception) {
            recordDenied(currentUser, exchange, AuditAction.RISK_CASE_LIST_VIEW, "latest");
            throw exception;
        }
    }

    @GetMapping("/{caseId}")
    public RiskCaseResponse get(
            @PathVariable Long caseId,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        try {
            RiskCaseResponse response = RiskCaseResponse.from(riskCaseService.staffCase(currentUser, caseId));
            recordSuccess(
                    currentUser,
                    exchange,
                    AuditAction.RISK_CASE_VIEW,
                    AuditResourceType.RISK_CASE,
                    String.valueOf(caseId),
                    response.studentUserId(),
                    Map.of("scope", "admin"));
            return response;
        } catch (ResponseStatusException exception) {
            recordDenied(currentUser, exchange, AuditAction.RISK_CASE_VIEW, String.valueOf(caseId));
            throw exception;
        }
    }

    @PatchMapping("/{caseId}/status")
    public RiskCaseResponse updateStatus(
            @PathVariable Long caseId,
            @Valid @RequestBody RiskCaseStatusRequest request,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        try {
            RiskCaseResponse response = RiskCaseResponse.from(
                    riskCaseService.transitionCase(
                            currentUser, caseId, request.status(), request.expectedVersion()));
            recordSuccess(
                    currentUser,
                    exchange,
                    AuditAction.RISK_CASE_STATUS_UPDATE,
                    AuditResourceType.RISK_CASE,
                    String.valueOf(caseId),
                    response.studentUserId(),
                    Map.of("scope", "admin", "status", request.status().name()));
            return response;
        } catch (ResponseStatusException exception) {
            recordDenied(currentUser, exchange, AuditAction.RISK_CASE_STATUS_UPDATE, String.valueOf(caseId));
            throw exception;
        } catch (OptimisticLockingFailureException exception) {
            recordFailure(
                    currentUser,
                    exchange,
                    AuditAction.RISK_CASE_STATUS_UPDATE,
                    AuditResourceType.RISK_CASE,
                    String.valueOf(caseId));
            throw exception;
        }
    }

    @GetMapping("/{caseId}/referrals")
    public List<ReferralResponse> referrals(
            @PathVariable Long caseId,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        try {
            List<ReferralResponse> referrals = riskCaseService.referrals(currentUser, caseId).stream()
                    .map(ReferralResponse::from)
                    .toList();
            recordSuccess(
                    currentUser,
                    exchange,
                    AuditAction.RISK_CASE_VIEW,
                    AuditResourceType.REFERRAL,
                    String.valueOf(caseId),
                    null,
                    Map.of("scope", "admin", "result_count", referrals.size()));
            return referrals;
        } catch (ResponseStatusException exception) {
            recordDenied(currentUser, exchange, AuditAction.RISK_CASE_VIEW, String.valueOf(caseId));
            throw exception;
        }
    }

    @PostMapping("/{caseId}/referrals")
    public ReferralResponse createReferral(
            @PathVariable Long caseId,
            @Valid @RequestBody ReferralCreateRequest request,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        try {
            ReferralResponse response = ReferralResponse.from(
                    riskCaseService.createReferral(currentUser, caseId, request));
            recordSuccess(
                    currentUser,
                    exchange,
                    AuditAction.REFERRAL_CREATE,
                    AuditResourceType.REFERRAL,
                    String.valueOf(caseId),
                    null,
                    Map.of("scope", "admin", "status", response.status().name()));
            return response;
        } catch (ResponseStatusException exception) {
            recordDenied(currentUser, exchange, AuditAction.REFERRAL_CREATE, String.valueOf(caseId));
            throw exception;
        } catch (OptimisticLockingFailureException exception) {
            recordFailure(
                    currentUser,
                    exchange,
                    AuditAction.REFERRAL_CREATE,
                    AuditResourceType.REFERRAL,
                    String.valueOf(caseId));
            throw exception;
        }
    }

    @PatchMapping("/{caseId}/referrals/{referralId}/status")
    public ReferralResponse updateReferralStatus(
            @PathVariable Long caseId,
            @PathVariable Long referralId,
            @Valid @RequestBody ReferralStatusRequest request,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        try {
            ReferralResponse response = ReferralResponse.from(
                    riskCaseService.transitionReferral(
                            currentUser, caseId, referralId, request.status(), request.expectedVersion()));
            recordSuccess(
                    currentUser,
                    exchange,
                    AuditAction.REFERRAL_STATUS_UPDATE,
                    AuditResourceType.REFERRAL,
                    String.valueOf(referralId),
                    null,
                    Map.of("scope", "admin", "status", request.status().name()));
            return response;
        } catch (ResponseStatusException exception) {
            recordDenied(currentUser, exchange, AuditAction.REFERRAL_STATUS_UPDATE, String.valueOf(referralId));
            throw exception;
        } catch (OptimisticLockingFailureException exception) {
            recordFailure(
                    currentUser,
                    exchange,
                    AuditAction.REFERRAL_STATUS_UPDATE,
                    AuditResourceType.REFERRAL,
                    String.valueOf(referralId));
            throw exception;
        }
    }

    @GetMapping("/{caseId}/interventions")
    public List<InterventionResponse> interventions(
            @PathVariable Long caseId,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        try {
            List<InterventionResponse> interventions = riskCaseService.interventions(currentUser, caseId).stream()
                    .map(InterventionResponse::from)
                    .toList();
            recordSuccess(
                    currentUser,
                    exchange,
                    AuditAction.RISK_CASE_VIEW,
                    AuditResourceType.INTERVENTION,
                    String.valueOf(caseId),
                    null,
                    Map.of("scope", "admin", "result_count", interventions.size()));
            return interventions;
        } catch (ResponseStatusException exception) {
            recordDenied(currentUser, exchange, AuditAction.RISK_CASE_VIEW, String.valueOf(caseId));
            throw exception;
        }
    }

    @PostMapping("/{caseId}/interventions")
    public InterventionResponse createIntervention(
            @PathVariable Long caseId,
            @Valid @RequestBody InterventionCreateRequest request,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        try {
            InterventionResponse response = InterventionResponse.from(
                    riskCaseService.recordIntervention(currentUser, caseId, request));
            recordSuccess(
                    currentUser,
                    exchange,
                    AuditAction.INTERVENTION_CREATE,
                    AuditResourceType.INTERVENTION,
                    String.valueOf(caseId),
                    null,
                    Map.of("scope", "admin", "status", "created"));
            return response;
        } catch (ResponseStatusException exception) {
            recordDenied(currentUser, exchange, AuditAction.INTERVENTION_CREATE, String.valueOf(caseId));
            throw exception;
        } catch (OptimisticLockingFailureException exception) {
            recordFailure(
                    currentUser,
                    exchange,
                    AuditAction.INTERVENTION_CREATE,
                    AuditResourceType.INTERVENTION,
                    String.valueOf(caseId));
            throw exception;
        }
    }

    private void recordSuccess(
            CurrentUser currentUser,
            ServerWebExchange exchange,
            AuditAction action,
            AuditResourceType resourceType,
            String resourceId,
            Long studentUserId,
            Map<String, ?> details
    ) {
        auditLogService.record(
                currentUser,
                action,
                resourceType,
                resourceId,
                AuditOutcome.SUCCESS,
                AuditRequestMetadata.from(exchange),
                studentUserId,
                details);
    }

    private void recordDenied(
            CurrentUser currentUser,
            ServerWebExchange exchange,
            AuditAction action,
            String resourceId
    ) {
        auditLogService.record(
                currentUser,
                action,
                AuditResourceType.RISK_CASE,
                resourceId,
                AuditOutcome.DENIED,
                AuditRequestMetadata.from(exchange),
                null,
                Map.of());
    }

    private void recordFailure(
            CurrentUser currentUser,
            ServerWebExchange exchange,
            AuditAction action,
            AuditResourceType resourceType,
            String resourceId
    ) {
        auditLogService.record(
                currentUser,
                action,
                resourceType,
                resourceId,
                AuditOutcome.FAILURE,
                AuditRequestMetadata.from(exchange),
                null,
                Map.of("reason", "optimistic_lock_conflict"));
    }
}
