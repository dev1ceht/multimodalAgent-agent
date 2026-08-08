package com.multimodalAgent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.AuditAction;
import com.multimodalAgent.agent.domain.AuditOutcome;
import com.multimodalAgent.agent.domain.AuditResourceType;
import com.multimodalAgent.agent.domain.DeliveryTask;
import com.multimodalAgent.agent.domain.DeliveryTaskType;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskCase;
import com.multimodalAgent.agent.domain.RiskCaseStatus;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.repository.DeliveryTaskRepository;
import com.multimodalAgent.agent.repository.RiskCaseRepository;
import com.multimodalAgent.agent.service.audit.AuditLogService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiskCaseEscalationServiceTests {

    private static final Instant NOW = Instant.parse("2026-08-08T04:00:00Z");

    @Mock
    private RiskCaseRepository riskCaseRepository;

    @Mock
    private DeliveryTaskRepository deliveryTaskRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private RiskCase riskCase;

    @Mock
    private PsychologicalReport report;

    @Mock
    private UserAccount student;

    private multimodalAgentProperties properties;
    private RiskCaseEscalationService service;

    @BeforeEach
    void setUp() {
        properties = new multimodalAgentProperties();
        properties.getMcp().getEmail().setRecipients(List.of(
                "ops@example.com",
                "counselor@example.com"));
        service = new RiskCaseEscalationService(
                riskCaseRepository,
                deliveryTaskRepository,
                auditLogService,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsOneDurableEscalationTaskPerRecipientAndAuditsTheEvent() {
        givenOverdueCase(42L, 7L);

        assertThat(service.scanOverdueCases()).isEqualTo(1);

        ArgumentCaptor<DeliveryTask> captor = ArgumentCaptor.forClass(DeliveryTask.class);
        verify(deliveryTaskRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(DeliveryTask::getTaskType, DeliveryTask::getRecipient,
                        DeliveryTask::getIdempotencyKey, DeliveryTask::getReport,
                        DeliveryTask::getRiskCase)
                .containsExactlyInAnyOrder(
                        Tuple.tuple(DeliveryTaskType.RISK_CASE_ESCALATION, "ops@example.com",
                                "risk-case-overdue:42:ops@example.com", report, riskCase),
                        Tuple.tuple(DeliveryTaskType.RISK_CASE_ESCALATION, "counselor@example.com",
                                "risk-case-overdue:42:counselor@example.com", report, riskCase));
        assertThat(captor.getAllValues())
                .allMatch(task -> task.getNextAttemptAt().equals(NOW));
        verify(riskCase).setOverdueEscalatedAt(NOW);
        verify(auditLogService).record(
                isNull(),
                eq(AuditAction.RISK_CASE_OVERDUE_ESCALATION),
                eq(AuditResourceType.RISK_CASE),
                eq("42"),
                eq(AuditOutcome.SUCCESS),
                isNull(),
                eq(7L),
                anyMap());
    }

    @Test
    void existingTaskIdentityStillMarksTheCaseWithoutCreatingDuplicateWork() {
        givenOverdueCase(42L, 7L);
        when(deliveryTaskRepository.findByIdempotencyKey(anyString()))
                .thenReturn(Optional.of(new DeliveryTask()));

        assertThat(service.scanOverdueCases()).isEqualTo(1);

        verify(deliveryTaskRepository, never()).save(any(DeliveryTask.class));
        verify(riskCase).setOverdueEscalatedAt(NOW);
        verify(auditLogService).record(
                isNull(),
                eq(AuditAction.RISK_CASE_OVERDUE_ESCALATION),
                eq(AuditResourceType.RISK_CASE),
                eq("42"),
                eq(AuditOutcome.SUCCESS),
                isNull(),
                eq(7L),
                anyMap());
    }

    @Test
    void missingRecipientsLeaveTheCaseEligibleForALaterConfigurationFix() {
        properties.getMcp().getEmail().setRecipients(List.of());
        givenOverdueCase(42L, 7L);

        assertThat(service.scanOverdueCases()).isZero();

        verify(deliveryTaskRepository, never()).findByIdempotencyKey(anyString());
        verify(deliveryTaskRepository, never()).save(any(DeliveryTask.class));
        verify(riskCase, never()).setOverdueEscalatedAt(any());
        verify(auditLogService, never()).record(
                any(), any(), any(), anyString(), any(), any(), any(), anyMap());
    }

    private void givenOverdueCase(Long caseId, Long studentId) {
        lenient().when(riskCase.getId()).thenReturn(caseId);
        lenient().when(riskCase.getTriggerReport()).thenReturn(report);
        lenient().when(riskCase.getStudentUser()).thenReturn(student);
        lenient().when(riskCase.getStatus()).thenReturn(RiskCaseStatus.OPEN);
        lenient().when(riskCase.getSlaDueAt()).thenReturn(NOW.minusSeconds(1));
        lenient().when(student.getId()).thenReturn(studentId);
        lenient().when(riskCaseRepository.findTop100BySlaDueAtBeforeAndOverdueEscalatedAtIsNullAndStatusInOrderBySlaDueAtAsc(
                eq(NOW), anyCollection(), any())).thenReturn(List.of(riskCase));
        lenient().when(deliveryTaskRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
    }
}
