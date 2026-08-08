package com.multimodalAgent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.domain.RiskCaseStatus;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.ReferralStatus;
import com.multimodalAgent.agent.domain.StudentStatus;
import com.multimodalAgent.agent.domain.UserRole;
import com.multimodalAgent.agent.dto.OperationsOverviewResponse;
import com.multimodalAgent.agent.dto.OperationsRiskCount;
import com.multimodalAgent.agent.dto.OperationsCaseStatusCount;
import com.multimodalAgent.agent.repository.InterventionRecordRepository;
import com.multimodalAgent.agent.repository.PsychologicalReportRepository;
import com.multimodalAgent.agent.repository.ReferralRepository;
import com.multimodalAgent.agent.repository.RiskCaseRepository;
import com.multimodalAgent.agent.repository.StudentProfileRepository;
import com.multimodalAgent.agent.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class OperationsOverviewServiceTests {

    private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");
    private static final Instant FROM = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant TO = NOW;

    @Mock
    private StudentProfileRepository studentProfileRepository;

    @Mock
    private PsychologicalReportRepository psychologicalReportRepository;

    @Mock
    private RiskCaseRepository riskCaseRepository;

    @Mock
    private ReferralRepository referralRepository;

    @Mock
    private InterventionRecordRepository interventionRecordRepository;

    @Test
    void schoolAdminGetsPopulationMetricsWithStableZeroBuckets() {
        when(studentProfileRepository.countByStatus(StudentStatus.ACTIVE)).thenReturn(87L);
        when(psychologicalReportRepository.aggregateStudentReportsByRiskLevel(FROM, TO))
                .thenReturn(List.of(
                        new OperationsRiskCount(RiskLevel.HIGH, 3),
                        new OperationsRiskCount(RiskLevel.LOW, 8)));
        when(riskCaseRepository.aggregateStudentCasesByStatus())
                .thenReturn(List.of(new OperationsCaseStatusCount(RiskCaseStatus.OPEN, 2)));
        when(riskCaseRepository.countBySlaDueAtBeforeAndStatusIn(
                NOW,
                List.of(
                        RiskCaseStatus.OPEN,
                        RiskCaseStatus.ACKNOWLEDGED,
                        RiskCaseStatus.REFERRED,
                        RiskCaseStatus.IN_PROGRESS)))
                .thenReturn(1L);
        when(referralRepository.countByStatusIn(List.of(ReferralStatus.PENDING, ReferralStatus.ACCEPTED)))
                .thenReturn(4L);
        when(referralRepository.countByDueAtBeforeAndStatusIn(
                NOW,
                List.of(ReferralStatus.PENDING, ReferralStatus.ACCEPTED)))
                .thenReturn(2L);
        when(interventionRecordRepository.countByOccurredAtGreaterThanEqualAndOccurredAtLessThan(FROM, TO))
                .thenReturn(6L);

        OperationsOverviewResponse response = service().overview(viewer(UserRole.SCHOOL_ADMIN), FROM, TO);

        assertThat(response.from()).isEqualTo(FROM);
        assertThat(response.to()).isEqualTo(TO);
        assertThat(response.generatedAt()).isEqualTo(NOW);
        assertThat(response.activeStudents()).isEqualTo(87L);
        assertThat(response.riskAssessmentsByLevel())
                .containsExactly(
                        new OperationsRiskCount(RiskLevel.NONE, 0),
                        new OperationsRiskCount(RiskLevel.LOW, 8),
                        new OperationsRiskCount(RiskLevel.MEDIUM, 0),
                        new OperationsRiskCount(RiskLevel.HIGH, 3));
        assertThat(response.casesByStatus())
                .containsExactly(
                        new OperationsCaseStatusCount(RiskCaseStatus.OPEN, 2),
                        new OperationsCaseStatusCount(RiskCaseStatus.ACKNOWLEDGED, 0),
                        new OperationsCaseStatusCount(RiskCaseStatus.REFERRED, 0),
                        new OperationsCaseStatusCount(RiskCaseStatus.IN_PROGRESS, 0),
                        new OperationsCaseStatusCount(RiskCaseStatus.RESOLVED, 0),
                        new OperationsCaseStatusCount(RiskCaseStatus.CLOSED, 0));
        assertThat(response.activeReferrals()).isEqualTo(4L);
        assertThat(response.overdueCases()).isEqualTo(1L);
        assertThat(response.overdueReferrals()).isEqualTo(2L);
        assertThat(response.interventionsInWindow()).isEqualTo(6L);
    }

    @Test
    void omittedWindowDefaultsToTheMostRecentThirtyDays() {
        when(studentProfileRepository.countByStatus(StudentStatus.ACTIVE)).thenReturn(0L);
        when(psychologicalReportRepository.aggregateStudentReportsByRiskLevel(
                Instant.parse("2026-07-09T00:00:00Z"), NOW)).thenReturn(List.of());
        when(riskCaseRepository.aggregateStudentCasesByStatus()).thenReturn(List.of());
        when(referralRepository.countByStatusIn(List.of(ReferralStatus.PENDING, ReferralStatus.ACCEPTED)))
                .thenReturn(0L);
        when(referralRepository.countByDueAtBeforeAndStatusIn(
                NOW,
                List.of(ReferralStatus.PENDING, ReferralStatus.ACCEPTED)))
                .thenReturn(0L);
        when(interventionRecordRepository.countByOccurredAtGreaterThanEqualAndOccurredAtLessThan(
                Instant.parse("2026-07-09T00:00:00Z"), NOW)).thenReturn(0L);

        OperationsOverviewResponse response = service().overview(viewer(UserRole.SCHOOL_ADMIN), null, null);

        assertThat(response.from()).isEqualTo(Instant.parse("2026-07-09T00:00:00Z"));
        assertThat(response.to()).isEqualTo(NOW);
    }

    @Test
    void nonSchoolAdminCannotReadOperationsOverview() {
        assertThatThrownBy(() -> service().overview(viewer(UserRole.COUNSELOR), FROM, TO))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        verify(studentProfileRepository, never()).countByStatus(StudentStatus.ACTIVE);
        verify(psychologicalReportRepository, never()).aggregateStudentReportsByRiskLevel(FROM, TO);
    }

    @Test
    void rejectsOneSidedOrOverlyLongWindows() {
        assertThatThrownBy(() -> service().overview(viewer(UserRole.SCHOOL_ADMIN), FROM, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThatThrownBy(() -> service().overview(
                viewer(UserRole.SCHOOL_ADMIN),
                Instant.parse("2024-08-07T00:00:00Z"),
                TO))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThatThrownBy(() -> service().overview(
                viewer(UserRole.SCHOOL_ADMIN),
                NOW.minusSeconds(366L * 24 * 60 * 60),
                NOW.minusSeconds(365L * 24 * 60 * 60)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThatThrownBy(() -> service().overview(
                viewer(UserRole.SCHOOL_ADMIN),
                FROM,
                NOW.plusSeconds(1)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private OperationsOverviewService service() {
        return new OperationsOverviewService(
                studentProfileRepository,
                psychologicalReportRepository,
                riskCaseRepository,
                referralRepository,
                interventionRecordRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CurrentUser viewer(UserRole role) {
        CurrentUser viewer = org.mockito.Mockito.mock(CurrentUser.class);
        org.mockito.Mockito.lenient().when(viewer.getId()).thenReturn(90L);
        org.mockito.Mockito.lenient().doReturn(List.of(new SimpleGrantedAuthority(role.authority())))
                .when(viewer).getAuthorities();
        return viewer;
    }
}
