package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.domain.AlertRecord;
import com.multimodalAgent.agent.domain.ChatMessage;
import com.multimodalAgent.agent.domain.ChatSession;
import com.multimodalAgent.agent.domain.MessageRole;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.ToolStatus;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.domain.UserRole;
import com.multimodalAgent.agent.dto.AlertRecordResponse;
import com.multimodalAgent.agent.dto.ConversationResponse;
import com.multimodalAgent.agent.dto.ExcelRecordResponse;
import com.multimodalAgent.agent.repository.AlertRecordRepository;
import com.multimodalAgent.agent.repository.ChatMessageRepository;
import com.multimodalAgent.agent.repository.ChatSessionRepository;
import com.multimodalAgent.agent.repository.PsychologicalReportRepository;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.security.DataScopeAuthorizationService;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReportService {

    private final PsychologicalReportRepository psychologicalReportRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AlertRecordRepository alertRecordRepository;
    private final DataScopeAuthorizationService dataScopeAuthorizationService;

    public ReportService(
            PsychologicalReportRepository psychologicalReportRepository,
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository,
            AlertRecordRepository alertRecordRepository,
            DataScopeAuthorizationService dataScopeAuthorizationService
    ) {
        this.psychologicalReportRepository = psychologicalReportRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.alertRecordRepository = alertRecordRepository;
        this.dataScopeAuthorizationService = dataScopeAuthorizationService;
    }

    @Transactional(readOnly = true)
    public List<PsychologicalReport> myReports(Long userId) {
        return psychologicalReportRepository.findTop50ByUser_IdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<PsychologicalReport> latestReports(CurrentUser viewer) {
        List<PsychologicalReport> candidates = scopedReports(viewer).stream()
                .filter(ReportService::isStudentReport)
                .toList();
        return dataScopeAuthorizationService.filterReports(viewer, candidates);
    }

    @Transactional(readOnly = true)
    public List<ExcelRecordResponse> excelRecords(CurrentUser viewer) {
        List<PsychologicalReport> candidates = scopedExcelReports(viewer).stream()
                .filter(ReportService::isStudentReport)
                .toList();
        return dataScopeAuthorizationService.filterReports(viewer, candidates).stream()
                .map(ExcelRecordResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AlertRecordResponse> alertRecords(CurrentUser viewer) {
        List<AlertRecord> candidates = scopedAlerts(viewer).stream()
                .filter(alertRecord -> isStudentReport(alertRecord.getReport()))
                .toList();
        return dataScopeAuthorizationService.filterAlerts(viewer, candidates).stream()
                .map(AlertRecordResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationResponse conversation(String sessionId, CurrentUser viewer) {
        ChatSession session = chatSessionRepository.findByPublicId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found"));
        boolean canViewAssignedUser = dataScopeAuthorizationService.canViewUser(viewer, session.getUser());
        boolean canViewReportedSession = psychologicalReportRepository
                .findBySession_PublicIdOrderByCreatedAtDesc(sessionId).stream()
                .anyMatch(report -> dataScopeAuthorizationService.canViewReport(viewer, report));
        if (!isStudentUser(session.getUser()) || (!canViewAssignedUser && !canViewReportedSession)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Conversation access denied");
        }
        List<ChatMessage> messages = chatMessageRepository.findBySession_PublicIdOrderByCreatedAtAsc(sessionId).stream()
                .filter(message -> message.getRole() != MessageRole.SYSTEM)
                .toList();
        return ConversationResponse.from(session, messages);
    }

    private List<PsychologicalReport> scopedReports(CurrentUser viewer) {
        Set<Long> studentUserIds = dataScopeAuthorizationService.visibleStudentUserIds(viewer);
        if (studentUserIds.isEmpty()) {
            return List.of();
        }
        if (dataScopeAuthorizationService.isPsychologyCenter(viewer)) {
            return psychologicalReportRepository
                    .findTop100ByUser_IdInAndRiskLevelOrderByCreatedAtDesc(studentUserIds, RiskLevel.HIGH);
        }
        return psychologicalReportRepository.findTop100ByUser_IdInOrderByCreatedAtDesc(studentUserIds);
    }

    private List<PsychologicalReport> scopedExcelReports(CurrentUser viewer) {
        Set<Long> studentUserIds = dataScopeAuthorizationService.visibleStudentUserIds(viewer);
        if (studentUserIds.isEmpty()) {
            return List.of();
        }
        if (dataScopeAuthorizationService.isPsychologyCenter(viewer)) {
            return psychologicalReportRepository
                    .findTop100ByUser_IdInAndExcelStatusAndRiskLevelOrderByCreatedAtDesc(
                            studentUserIds,
                            ToolStatus.SUCCESS,
                            RiskLevel.HIGH);
        }
        return psychologicalReportRepository.findTop100ByUser_IdInAndExcelStatusOrderByCreatedAtDesc(
                studentUserIds,
                ToolStatus.SUCCESS);
    }

    private List<AlertRecord> scopedAlerts(CurrentUser viewer) {
        Set<Long> studentUserIds = dataScopeAuthorizationService.visibleStudentUserIds(viewer);
        if (studentUserIds.isEmpty()) {
            return List.of();
        }
        if (dataScopeAuthorizationService.isPsychologyCenter(viewer)) {
            return alertRecordRepository
                    .findTop100ByReport_User_IdInAndReport_RiskLevelOrderByCreatedAtDesc(
                            studentUserIds,
                            RiskLevel.HIGH);
        }
        return alertRecordRepository.findTop100ByReport_User_IdInOrderByCreatedAtDesc(studentUserIds);
    }

    private static boolean isStudentReport(PsychologicalReport report) {
        return report != null && isStudentUser(report.getUser());
    }

    private static boolean isStudentUser(UserAccount user) {
        return user != null && UserRole.isStudentAccount(user.getRoles());
    }
}
