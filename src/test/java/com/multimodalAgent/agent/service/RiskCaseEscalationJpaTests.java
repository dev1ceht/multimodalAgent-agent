package com.multimodalAgent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.multimodalAgent.agent.config.OperationsConfig;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.AuditAction;
import com.multimodalAgent.agent.domain.DeliveryTask;
import com.multimodalAgent.agent.domain.DeliveryTaskType;
import com.multimodalAgent.agent.domain.EmotionLabel;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskCase;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.domain.UserRole;
import com.multimodalAgent.agent.repository.AuditLogRepository;
import com.multimodalAgent.agent.repository.DeliveryTaskRepository;
import com.multimodalAgent.agent.repository.PsychologicalReportRepository;
import com.multimodalAgent.agent.repository.RiskCaseRepository;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import com.multimodalAgent.agent.service.audit.AuditLogService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@Import({
        OperationsConfig.class,
        RiskCaseEscalationService.class,
        AuditLogService.class
})
@EnableConfigurationProperties(multimodalAgentProperties.class)
class RiskCaseEscalationJpaTests {

    private static final Instant NOW = Instant.parse("2026-08-08T04:00:00Z");

    @jakarta.annotation.Resource
    private RiskCaseEscalationService service;

    @jakarta.annotation.Resource
    private RiskCaseRepository riskCaseRepository;

    @jakarta.annotation.Resource
    private DeliveryTaskRepository deliveryTaskRepository;

    @jakarta.annotation.Resource
    private AuditLogRepository auditLogRepository;

    @jakarta.annotation.Resource
    private PsychologicalReportRepository reportRepository;

    @jakarta.annotation.Resource
    private UserAccountRepository userAccountRepository;

    @jakarta.annotation.Resource
    private multimodalAgentProperties properties;

    @BeforeEach
    void setUp() {
        properties.getMcp().getEmail().setRecipients(List.of("ops@example.com"));
    }

    @Test
    void persistsAnEscalationTaskMarkerAndAuditEventAsOnePublicOperation() {
        PsychologicalReport report = saveHighRiskReport();
        RiskCase riskCase = new RiskCase();
        riskCase.setTriggerReport(report);
        riskCase.setStudentUser(report.getUser());
        riskCase.setRiskLevel(RiskLevel.HIGH);
        riskCase.setOpeningReason("High-risk assessment requires human follow-up.");
        riskCase.setSlaDueAt(NOW.minusSeconds(1));
        riskCase = riskCaseRepository.saveAndFlush(riskCase);

        assertThat(service.scanOverdueCases()).isEqualTo(1);

        RiskCase persistedCase = riskCaseRepository.findById(riskCase.getId()).orElseThrow();
        assertThat(persistedCase.getOverdueEscalatedAt()).isNotNull();
        List<DeliveryTask> tasks = deliveryTaskRepository.findByReport_Id(report.getId());
        assertThat(tasks)
                .extracting(DeliveryTask::getTaskType, DeliveryTask::getRecipient,
                        DeliveryTask::getIdempotencyKey, DeliveryTask::getRiskCase)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        DeliveryTaskType.RISK_CASE_ESCALATION,
                        "ops@example.com",
                        "risk-case-overdue:" + riskCase.getId() + ":ops@example.com",
                        persistedCase));
        assertThat(auditLogRepository.findTop100ByOrderByCreatedAtDesc())
                .filteredOn(log -> log.getAction() == AuditAction.RISK_CASE_OVERDUE_ESCALATION)
                .extracting(log -> log.getDetails())
                .containsExactly("status=overdue");
    }

    private PsychologicalReport saveHighRiskReport() {
        UserAccount user = new UserAccount();
        user.setUsername("student-escalation");
        user.setPassword("password");
        user.setDisplayName("Escalation Student");
        user.setRoles(java.util.Set.of(UserRole.STUDENT.authority()));
        user = userAccountRepository.saveAndFlush(user);

        PsychologicalReport report = new PsychologicalReport();
        report.setUser(user);
        report.setContent("student content");
        report.setNeedsRag(true);
        report.setEmotion(EmotionLabel.HIGH_RISK);
        report.setEmotionScore(4.0);
        report.setRiskLevel(RiskLevel.HIGH);
        report.setConfidence(0.95);
        report.setSummary("high risk signal");
        return reportRepository.saveAndFlush(report);
    }
}
