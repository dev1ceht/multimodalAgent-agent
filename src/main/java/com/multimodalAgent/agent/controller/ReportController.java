package com.multimodalAgent.agent.controller;

import com.multimodalAgent.agent.domain.AuditAction;
import com.multimodalAgent.agent.domain.AuditOutcome;
import com.multimodalAgent.agent.domain.AuditResourceType;
import com.multimodalAgent.agent.dto.AlertRecordResponse;
import com.multimodalAgent.agent.dto.ConversationResponse;
import com.multimodalAgent.agent.dto.ExcelRecordResponse;
import com.multimodalAgent.agent.dto.ReportResponse;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.service.ReportService;
import com.multimodalAgent.agent.service.audit.AuditLogService;
import com.multimodalAgent.agent.service.audit.AuditRequestMetadata;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

@RestController
@RequestMapping("/api")
public class ReportController {

    private final ReportService reportService;
    private final AuditLogService auditLogService;

    public ReportController(ReportService reportService, AuditLogService auditLogService) {
        this.reportService = reportService;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/reports/me")
    public List<ReportResponse> myReports(
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        List<ReportResponse> reports = reportService.myReports(currentUser.getId()).stream()
                .map(ReportResponse::from)
                .toList();
        auditLogService.record(
                currentUser,
                AuditAction.REPORT_SELF_VIEW,
                AuditResourceType.REPORT,
                "self",
                AuditOutcome.SUCCESS,
                AuditRequestMetadata.from(exchange),
                currentUser.getId(),
                Map.of("scope", "self", "result_count", reports.size()));
        return reports;
    }

    @GetMapping("/admin/reports")
    public List<ReportResponse> latestReports(
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        List<ReportResponse> reports = reportService.latestReports(currentUser).stream()
                .map(ReportResponse::from)
                .toList();
        auditLogService.record(
                currentUser,
                AuditAction.REPORT_LIST_VIEW,
                AuditResourceType.REPORT,
                "latest",
                AuditOutcome.SUCCESS,
                AuditRequestMetadata.from(exchange),
                null,
                Map.of("scope", "admin", "result_count", reports.size()));
        return reports;
    }

    @GetMapping("/admin/excel-records")
    public List<ExcelRecordResponse> excelRecords(
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        List<ExcelRecordResponse> records = reportService.excelRecords(currentUser);
        auditLogService.record(
                currentUser,
                AuditAction.EXCEL_RECORD_VIEW,
                AuditResourceType.EXCEL_RECORD,
                "latest",
                AuditOutcome.SUCCESS,
                AuditRequestMetadata.from(exchange),
                null,
                Map.of("scope", "admin", "result_count", records.size()));
        return records;
    }

    @GetMapping("/admin/alerts")
    public List<AlertRecordResponse> alertRecords(
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        List<AlertRecordResponse> records = reportService.alertRecords(currentUser);
        auditLogService.record(
                currentUser,
                AuditAction.ALERT_LIST_VIEW,
                AuditResourceType.ALERT,
                "latest",
                AuditOutcome.SUCCESS,
                AuditRequestMetadata.from(exchange),
                null,
                Map.of("scope", "admin", "result_count", records.size()));
        return records;
    }

    @GetMapping("/admin/conversations/{sessionId}")
    public ConversationResponse conversation(
            @PathVariable String sessionId,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        try {
            ConversationResponse response = reportService.conversation(sessionId, currentUser);
            auditLogService.record(
                    currentUser,
                    AuditAction.CONVERSATION_VIEW,
                    AuditResourceType.CONVERSATION,
                    sessionId,
                    AuditOutcome.SUCCESS,
                    AuditRequestMetadata.from(exchange),
                    null,
                    Map.of("scope", "admin"));
            return response;
        } catch (IllegalArgumentException | ResponseStatusException exception) {
            auditLogService.record(
                    currentUser,
                    AuditAction.CONVERSATION_VIEW,
                    AuditResourceType.CONVERSATION,
                    sessionId,
                    AuditOutcome.DENIED,
                    AuditRequestMetadata.from(exchange),
                    null,
                    Map.of());
            throw exception;
        }
    }
}
