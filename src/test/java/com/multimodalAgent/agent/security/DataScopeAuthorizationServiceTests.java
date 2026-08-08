package com.multimodalAgent.agent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.CounselorAssignment;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.domain.UserRole;
import com.multimodalAgent.agent.domain.StudentProfile;
import com.multimodalAgent.agent.repository.CounselorAssignmentRepository;
import com.multimodalAgent.agent.repository.StudentProfileRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.security.core.GrantedAuthority;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@ExtendWith(MockitoExtension.class)
class DataScopeAuthorizationServiceTests {

    @Mock
    private StudentProfileRepository studentProfileRepository;

    @Mock
    private CounselorAssignmentRepository counselorAssignmentRepository;

    @Test
    void studentCanViewTheirOwnReport() {
        DataScopeAuthorizationService service = service();
        CurrentUser student = viewer(10L, UserRole.STUDENT);
        PsychologicalReport report = reportOwnedBy(10L);

        assertThat(service.canViewReport(student, report)).isTrue();
    }

    @Test
    void counselorCanViewOnlyReportsCoveredByAnEnabledAssignment() {
        DataScopeAuthorizationService service = service();
        CurrentUser counselor = viewer(20L, UserRole.COUNSELOR);
        PsychologicalReport report = reportOwnedBy(30L);
        StudentProfile profile = mock(StudentProfile.class);
        CounselorAssignment assignment = mock(CounselorAssignment.class);
        when(studentProfileRepository.findByUser_Id(30L)).thenReturn(Optional.of(profile));
        when(counselorAssignmentRepository.findByCounselor_IdAndEnabledTrue(20L))
                .thenReturn(List.of(assignment));
        when(assignment.covers(profile)).thenReturn(true);

        assertThat(service.canViewReport(counselor, report)).isTrue();

        when(assignment.covers(profile)).thenReturn(false);
        assertThat(service.canViewReport(counselor, report)).isFalse();
    }

    @Test
    void psychologyCenterCanViewHighRiskReportsButNotLowerRiskReports() {
        DataScopeAuthorizationService service = service();
        CurrentUser reviewer = viewer(40L, UserRole.PSYCHOLOGY_CENTER);
        PsychologicalReport report = reportOwnedBy(30L);
        when(report.getRiskLevel()).thenReturn(com.multimodalAgent.agent.domain.RiskLevel.HIGH);

        assertThat(service.canViewReport(reviewer, report)).isTrue();

        when(report.getRiskLevel()).thenReturn(com.multimodalAgent.agent.domain.RiskLevel.MEDIUM);
        assertThat(service.canViewReport(reviewer, report)).isFalse();
    }

    @Test
    void psychologyCenterCannotUseSelfOwnershipToBypassHighRiskRule() {
        DataScopeAuthorizationService service = service();
        CurrentUser reviewer = viewer(40L, UserRole.PSYCHOLOGY_CENTER);
        PsychologicalReport report = reportOwnedBy(40L);
        when(report.getRiskLevel()).thenReturn(com.multimodalAgent.agent.domain.RiskLevel.MEDIUM);

        assertThat(service.canViewReport(reviewer, report)).isFalse();
    }

    @Test
    void systemAdminCannotViewSensitiveReportsWithoutAReviewRole() {
        DataScopeAuthorizationService service = service();

        assertThat(service.canViewReport(viewer(50L, UserRole.SYSTEM_ADMIN), reportOwnedBy(30L)))
                .isFalse();
    }

    private DataScopeAuthorizationService service() {
        return new DataScopeAuthorizationService(studentProfileRepository, counselorAssignmentRepository);
    }

    private CurrentUser viewer(Long id, UserRole role) {
        CurrentUser user = mock(CurrentUser.class);
        lenient().when(user.getId()).thenReturn(id);
        if (role != null) {
            List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(role.authority()));
            doReturn(authorities).when(user).getAuthorities();
        }
        return user;
    }

    private PsychologicalReport reportOwnedBy(Long userId) {
        UserAccount owner = mock(UserAccount.class);
        lenient().when(owner.getId()).thenReturn(userId);
        lenient().when(owner.getRoles()).thenReturn(java.util.Set.of(UserRole.STUDENT.authority()));
        PsychologicalReport report = mock(PsychologicalReport.class);
        when(report.getUser()).thenReturn(owner);
        return report;
    }
}
