package com.multimodalAgent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.domain.ChatSession;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.domain.UserRole;
import com.multimodalAgent.agent.repository.AlertRecordRepository;
import com.multimodalAgent.agent.repository.ChatMessageRepository;
import com.multimodalAgent.agent.repository.ChatSessionRepository;
import com.multimodalAgent.agent.repository.PsychologicalReportRepository;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.security.DataScopeAuthorizationService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ReportServiceAuthorizationTests {

    @Mock
    private PsychologicalReportRepository psychologicalReportRepository;

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private AlertRecordRepository alertRecordRepository;

    @Mock
    private DataScopeAuthorizationService dataScopeAuthorizationService;

    @Test
    void conversationCanUseAnOlderAuthorizedReportInTheSameSession() {
        CurrentUser viewer = mock(CurrentUser.class);
        UserAccount student = new UserAccount();
        student.setRoles(Set.of(UserRole.STUDENT.authority()));
        ChatSession session = new ChatSession();
        session.setPublicId("session-1");
        session.setUser(student);
        PsychologicalReport latestLowRisk = mock(PsychologicalReport.class);
        PsychologicalReport olderHighRisk = mock(PsychologicalReport.class);

        when(chatSessionRepository.findByPublicId("session-1")).thenReturn(Optional.of(session));
        when(psychologicalReportRepository.findBySession_PublicIdOrderByCreatedAtDesc("session-1"))
                .thenReturn(List.of(latestLowRisk, olderHighRisk));
        when(dataScopeAuthorizationService.canViewUser(viewer, student)).thenReturn(false);
        when(dataScopeAuthorizationService.canViewReport(viewer, latestLowRisk)).thenReturn(false);
        when(dataScopeAuthorizationService.canViewReport(viewer, olderHighRisk)).thenReturn(true);
        when(chatMessageRepository.findBySession_PublicIdOrderByCreatedAtAsc("session-1"))
                .thenReturn(List.of());

        assertThat(service().conversation("session-1", viewer)).isNotNull();
    }

    @Test
    void conversationScopeMismatchReturnsForbidden() {
        CurrentUser viewer = mock(CurrentUser.class);
        UserAccount student = new UserAccount();
        student.setRoles(Set.of(UserRole.STUDENT.authority()));
        ChatSession session = new ChatSession();
        session.setPublicId("session-2");
        session.setUser(student);
        PsychologicalReport report = mock(PsychologicalReport.class);

        when(chatSessionRepository.findByPublicId("session-2")).thenReturn(Optional.of(session));
        when(psychologicalReportRepository.findBySession_PublicIdOrderByCreatedAtDesc("session-2"))
                .thenReturn(List.of(report));
        when(dataScopeAuthorizationService.canViewUser(viewer, student)).thenReturn(false);
        when(dataScopeAuthorizationService.canViewReport(viewer, report)).thenReturn(false);

        assertThatThrownBy(() -> service().conversation("session-2", viewer))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private ReportService service() {
        return new ReportService(
                psychologicalReportRepository,
                chatSessionRepository,
                chatMessageRepository,
                alertRecordRepository,
                dataScopeAuthorizationService);
    }
}
