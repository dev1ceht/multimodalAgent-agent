package com.multimodalAgent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.domain.InterventionType;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.ReferralTargetType;
import com.multimodalAgent.agent.domain.RiskCase;
import com.multimodalAgent.agent.domain.RiskCaseStatus;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.domain.UserRole;
import com.multimodalAgent.agent.config.RiskCaseSlaProperties;
import com.multimodalAgent.agent.config.RiskResponseRule;
import com.multimodalAgent.agent.dto.InterventionCreateRequest;
import com.multimodalAgent.agent.dto.ReferralCreateRequest;
import com.multimodalAgent.agent.repository.InterventionRecordRepository;
import com.multimodalAgent.agent.repository.ReferralRepository;
import com.multimodalAgent.agent.repository.RiskCaseRepository;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.security.DataScopeAuthorizationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class RiskCaseServiceTests {

    @Mock
    private RiskCaseRepository riskCaseRepository;

    @Mock
    private ReferralRepository referralRepository;

    @Mock
    private InterventionRecordRepository interventionRecordRepository;

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private DataScopeAuthorizationService dataScopeAuthorizationService;

    private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");

    @Test
    void highRiskStudentReportOpensAtMostOneCase() {
        PsychologicalReport report = report(42L, RiskLevel.HIGH);
        RiskCase existing = new RiskCase();
        when(riskCaseRepository.findByTriggerReport_Id(42L))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(riskCaseRepository.save(any(RiskCase.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RiskCaseService service = service();

        assertThat(service.ensureCaseForReport(report)).isPresent();
        assertThat(service.ensureCaseForReport(report)).containsSame(existing);
        verify(riskCaseRepository, times(1)).save(any(RiskCase.class));
    }

    @Test
    void caseCreationFollowsTheConfiguredRiskResponsePolicy() {
        RiskCaseSlaProperties properties = slaProperties();
        properties.setResponsePolicy(java.util.Map.of(
                RiskLevel.HIGH,
                new RiskResponseRule(false, true)));
        RiskCaseService service = service(properties);

        assertThat(service.ensureCaseForReport(report(42L, RiskLevel.HIGH))).isEmpty();
        verify(riskCaseRepository, never()).findByTriggerReport_Id(42L);
        verify(riskCaseRepository, never()).save(any(RiskCase.class));
    }

    @Test
    void lowerRiskReportDoesNotOpenHumanFollowUpCase() {
        assertThat(service().ensureCaseForReport(report(42L, RiskLevel.MEDIUM))).isEmpty();
        verify(riskCaseRepository, times(0)).findByTriggerReport_Id(42L);
    }

    @Test
    void highRiskCaseGetsConfiguredSlaDeadline() {
        when(riskCaseRepository.findByTriggerReport_Id(42L)).thenReturn(Optional.empty());
        when(riskCaseRepository.save(any(RiskCase.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RiskCase riskCase = service().ensureCaseForReport(report(42L, RiskLevel.HIGH)).orElseThrow();

        assertThat(riskCase.getSlaDueAt()).isEqualTo(NOW.plus(Duration.ofHours(4)));
    }

    @Test
    void assignedCounselorCanAcknowledgeCase() {
        CurrentUser counselor = viewer(20L, UserRole.COUNSELOR);
        UserAccount student = student(30L);
        RiskCase riskCase = caseFor(student, RiskLevel.HIGH);
        when(riskCaseRepository.findById(7L)).thenReturn(Optional.of(riskCase));
        when(dataScopeAuthorizationService.canViewUser(counselor, student)).thenReturn(true);
        when(riskCaseRepository.saveAndFlush(riskCase)).thenReturn(riskCase);

        assertThat(service().transitionCase(counselor, 7L, RiskCaseStatus.ACKNOWLEDGED).getStatus())
                .isEqualTo(RiskCaseStatus.ACKNOWLEDGED);
    }

    @Test
    void staffCanListEnabledCounselorReferralTargets() {
        CurrentUser reviewer = viewer(40L, UserRole.PSYCHOLOGY_CENTER);
        UserAccount counselor = student(50L);
        when(userAccountRepository.findEnabledByRole(UserRole.COUNSELOR.authority()))
                .thenReturn(List.of(counselor));

        assertThat(service().counselorReferralTargets(reviewer)).containsExactly(counselor);
    }

    @Test
    void counselorOutsideAssignmentCannotManageCase() {
        CurrentUser counselor = viewer(20L, UserRole.COUNSELOR);
        UserAccount student = student(30L);
        when(riskCaseRepository.findById(7L)).thenReturn(Optional.of(caseFor(student, RiskLevel.HIGH)));
        when(dataScopeAuthorizationService.canViewUser(counselor, student)).thenReturn(false);

        assertThatThrownBy(() -> service().transitionCase(counselor, 7L, RiskCaseStatus.ACKNOWLEDGED))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void staleExpectedVersionCannotTransitionCase() {
        CurrentUser counselor = viewer(20L, UserRole.COUNSELOR);
        UserAccount student = student(30L);
        RiskCase riskCase = caseFor(student, RiskLevel.HIGH);
        when(riskCaseRepository.findById(7L)).thenReturn(Optional.of(riskCase));
        when(dataScopeAuthorizationService.canViewUser(counselor, student)).thenReturn(true);

        assertThatThrownBy(() -> service().transitionCase(
                counselor, 7L, RiskCaseStatus.ACKNOWLEDGED, 9L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        verify(riskCaseRepository, times(0)).saveAndFlush(any(RiskCase.class));
    }

    @Test
    void referralMovesCaseToReferredAndInterventionMovesItToInProgress() {
        CurrentUser reviewer = viewer(40L, UserRole.PSYCHOLOGY_CENTER);
        UserAccount student = student(30L);
        UserAccount counselor = student(50L);
        when(counselor.getRoles()).thenReturn(Set.of(UserRole.COUNSELOR.authority()));
        UserAccount reviewerAccount = student(40L);
        RiskCase riskCase = caseFor(student, RiskLevel.HIGH);
        riskCase.transitionTo(RiskCaseStatus.ACKNOWLEDGED);
        when(riskCaseRepository.findById(7L)).thenReturn(Optional.of(riskCase));
        when(userAccountRepository.findById(40L)).thenReturn(Optional.of(reviewerAccount));
        when(userAccountRepository.findById(50L)).thenReturn(Optional.of(counselor));
        when(referralRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(interventionRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(riskCaseRepository.saveAndFlush(riskCase)).thenReturn(riskCase);

        RiskCaseService service = service();
        service.createReferral(
                reviewer,
                7L,
                new ReferralCreateRequest(
                        ReferralTargetType.COUNSELOR,
                        50L,
                        "Counselor follow-up required",
                        Instant.parse("2026-08-15T09:00:00Z")));
        assertThat(riskCase.getStatus()).isEqualTo(RiskCaseStatus.REFERRED);

        service.recordIntervention(
                reviewer,
                7L,
                new InterventionCreateRequest(
                        InterventionType.CHECK_IN,
                        "Initial safety check completed",
                        "Student agreed to a follow-up",
                        Instant.parse("2026-08-09T09:00:00Z"),
                        Instant.parse("2026-08-16T09:00:00Z")));
        assertThat(riskCase.getStatus()).isEqualTo(RiskCaseStatus.IN_PROGRESS);
        verify(referralRepository).saveAndFlush(any());
        verify(interventionRecordRepository).save(any());
    }

    @Test
    void referralWithoutExplicitDueAtUsesConfiguredSla() {
        CurrentUser reviewer = viewer(40L, UserRole.PSYCHOLOGY_CENTER);
        UserAccount student = student(30L);
        UserAccount reviewerAccount = student(40L);
        RiskCase riskCase = caseFor(student, RiskLevel.HIGH);
        when(riskCaseRepository.findById(7L)).thenReturn(Optional.of(riskCase));
        when(userAccountRepository.findById(40L)).thenReturn(Optional.of(reviewerAccount));
        when(referralRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(riskCaseRepository.saveAndFlush(riskCase)).thenReturn(riskCase);

        ReferralCreateRequest request = new ReferralCreateRequest(
                ReferralTargetType.PSYCHOLOGY_CENTER,
                null,
                "Psychology center follow-up required",
                null);

        com.multimodalAgent.agent.domain.Referral referral = service().createReferral(reviewer, 7L, request);

        assertThat(referral.getDueAt()).isEqualTo(NOW.plus(Duration.ofHours(48)));
    }

    private RiskCaseService service() {
        return service(slaProperties());
    }

    private RiskCaseService service(RiskCaseSlaProperties properties) {
        return new RiskCaseService(
                riskCaseRepository,
                referralRepository,
                interventionRecordRepository,
                userAccountRepository,
                dataScopeAuthorizationService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                properties);
    }

    private RiskCaseSlaProperties slaProperties() {
        RiskCaseSlaProperties properties = new RiskCaseSlaProperties();
        properties.setHighRiskCaseResponse(Duration.ofHours(4));
        properties.setReferralResponse(Duration.ofHours(48));
        return properties;
    }

    private PsychologicalReport report(Long id, RiskLevel riskLevel) {
        UserAccount student = student(30L);
        PsychologicalReport report = org.mockito.Mockito.mock(PsychologicalReport.class);
        lenient().when(report.getId()).thenReturn(id);
        lenient().when(report.getUser()).thenReturn(student);
        lenient().when(report.getRiskLevel()).thenReturn(riskLevel);
        return report;
    }

    private RiskCase caseFor(UserAccount student, RiskLevel riskLevel) {
        RiskCase riskCase = new RiskCase();
        riskCase.setStudentUser(student);
        riskCase.setRiskLevel(riskLevel);
        return riskCase;
    }

    private UserAccount student(Long id) {
        UserAccount student = org.mockito.Mockito.mock(UserAccount.class);
        lenient().when(student.getId()).thenReturn(id);
        lenient().when(student.getRoles()).thenReturn(Set.of(UserRole.STUDENT.authority()));
        return student;
    }

    private CurrentUser viewer(Long id, UserRole role) {
        CurrentUser viewer = org.mockito.Mockito.mock(CurrentUser.class);
        lenient().when(viewer.getId()).thenReturn(id);
        lenient().doReturn(List.of(new SimpleGrantedAuthority(role.authority())))
                .when(viewer).getAuthorities();
        return viewer;
    }
}
