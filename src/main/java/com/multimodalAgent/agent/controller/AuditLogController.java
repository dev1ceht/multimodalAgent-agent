package com.multimodalAgent.agent.controller;

import com.multimodalAgent.agent.dto.AuditLogResponse;
import com.multimodalAgent.agent.service.audit.AuditLogService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/audit-logs")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public List<AuditLogResponse> latest() {
        return auditLogService.latest().stream()
                .map(AuditLogResponse::from)
                .toList();
    }
}
