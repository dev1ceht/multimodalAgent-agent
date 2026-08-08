package com.multimodalAgent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.AlertRecord;
import com.multimodalAgent.agent.domain.DeliveryTask;
import com.multimodalAgent.agent.domain.DeliveryTaskStatus;
import com.multimodalAgent.agent.domain.DeliveryTaskType;
import com.multimodalAgent.agent.domain.EmotionLabel;
import com.multimodalAgent.agent.domain.NotificationAttemptStatus;
import com.multimodalAgent.agent.domain.NotificationRecord;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.ToolStatus;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.domain.UserRole;
import com.multimodalAgent.agent.repository.AlertRecordRepository;
import com.multimodalAgent.agent.repository.DeliveryTaskRepository;
import com.multimodalAgent.agent.repository.NotificationRecordRepository;
import com.multimodalAgent.agent.repository.PsychologicalReportRepository;
import com.multimodalAgent.agent.repository.RiskCaseRepository;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import com.multimodalAgent.agent.security.DataScopeAuthorizationService;
import com.multimodalAgent.agent.service.mcp.AlertNotifier;
import com.multimodalAgent.agent.service.mcp.ExcelReportWriter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@Import({
        ToolOrchestrationService.class,
        ExternalDeliveryTaskExecutor.class,
        JpaNotificationAttemptRecorder.class,
        RiskCaseService.class,
        DataScopeAuthorizationService.class,
        ToolOrchestrationServiceTests.TestConfig.class
})
class ToolOrchestrationServiceTests {

    @MockBean
    private ExcelReportWriter excelReportWriter;

    @MockBean
    private AlertNotifier alertNotifier;

    @jakarta.annotation.Resource
    private PsychologicalReportRepository reportRepository;

    @jakarta.annotation.Resource
    private UserAccountRepository userAccountRepository;

    @jakarta.annotation.Resource
    private AlertRecordRepository alertRecordRepository;

    @jakarta.annotation.Resource
    private DeliveryTaskRepository deliveryTaskRepository;

    @jakarta.annotation.Resource
    private NotificationRecordRepository notificationRecordRepository;

    @jakarta.annotation.Resource
    private RiskCaseRepository riskCaseRepository;

    @jakarta.annotation.Resource
    private ToolOrchestrationService toolOrchestrationService;

    @jakarta.annotation.Resource
    private multimodalAgentProperties properties;

    @BeforeEach
    void setUp() {
        properties.getDelivery().setMaxAttempts(1);
    }

    @Test
    void excelFailureDoesNotSuppressIndependentHighRiskAlert() {
        doThrow(new IllegalStateException("excel unavailable"))
                .when(excelReportWriter)
                .write(any(PsychologicalReport.class), anyString());

        Long reportId = saveHighRiskReport().getId();

        toolOrchestrationService.handle(reportId);

        PsychologicalReport report = reportRepository.findById(reportId).orElseThrow();
        assertThat(report.getExcelStatus()).isEqualTo(ToolStatus.FAILED);
        assertThat(report.getEmailStatus()).isEqualTo(ToolStatus.SUCCESS);
        verify(alertNotifier).notify(any(AlertRecord.class), any(PsychologicalReport.class), anyString());

        List<DeliveryTask> tasks = deliveryTaskRepository.findByReport_Id(reportId);
        assertThat(tasks).hasSize(2);
        assertThat(tasks).extracting(DeliveryTask::getTaskType)
                .containsExactlyInAnyOrder(DeliveryTaskType.EXCEL_EXPORT, DeliveryTaskType.ALERT_NOTIFICATION);
        assertThat(tasks).filteredOn(task -> task.getTaskType() == DeliveryTaskType.EXCEL_EXPORT)
                .extracting(DeliveryTask::getStatus)
                .containsExactly(DeliveryTaskStatus.FAILED);
        assertThat(tasks).filteredOn(task -> task.getTaskType() == DeliveryTaskType.ALERT_NOTIFICATION)
                .extracting(DeliveryTask::getStatus)
                .containsExactly(DeliveryTaskStatus.SUCCEEDED);
        DeliveryTask alertTask = tasks.stream()
                .filter(task -> task.getTaskType() == DeliveryTaskType.ALERT_NOTIFICATION)
                .findFirst()
                .orElseThrow();
        assertThat(notificationRecordRepository.findByDeliveryTask_IdOrderByAttemptNumberAsc(alertTask.getId()))
                .extracting(record -> record.getStatus())
                .containsExactly(NotificationAttemptStatus.SUCCESS);
    }

    @Test
    void repeatedHandlingIsIdempotentAfterTasksAreCreated() {
        Long reportId = saveHighRiskReport().getId();

        toolOrchestrationService.handle(reportId);
        toolOrchestrationService.handle(reportId);

        assertThat(deliveryTaskRepository.findByReport_Id(reportId)).hasSize(2);
        assertThat(riskCaseRepository.findByTriggerReport_Id(reportId)).isPresent();
        assertThat(alertRecordRepository.findByReport_Id(reportId)).hasSize(1);
        DeliveryTask alertTask = deliveryTaskRepository.findByReport_Id(reportId).stream()
                .filter(task -> task.getTaskType() == DeliveryTaskType.ALERT_NOTIFICATION)
                .findFirst()
                .orElseThrow();
        assertThat(notificationRecordRepository.findByDeliveryTask_IdOrderByAttemptNumberAsc(alertTask.getId()))
                .hasSize(1);
        verify(alertNotifier, times(1))
                .notify(any(AlertRecord.class), any(PsychologicalReport.class), anyString());
    }

    @Test
    void transientExcelFailureMovesToRetryWithoutBlockingAlert() {
        properties.getDelivery().setMaxAttempts(2);
        doThrow(new IllegalStateException("temporary Excel outage"))
                .when(excelReportWriter)
                .write(any(PsychologicalReport.class), anyString());

        Long reportId = saveHighRiskReport().getId();

        toolOrchestrationService.handle(reportId);

        PsychologicalReport report = reportRepository.findById(reportId).orElseThrow();
        assertThat(report.getExcelStatus()).isEqualTo(ToolStatus.PENDING);
        assertThat(report.getEmailStatus()).isEqualTo(ToolStatus.SUCCESS);
        DeliveryTask excelTask = deliveryTaskRepository.findByReport_Id(reportId).stream()
                .filter(task -> task.getTaskType() == DeliveryTaskType.EXCEL_EXPORT)
                .findFirst()
                .orElseThrow();
        assertThat(excelTask.getStatus()).isEqualTo(DeliveryTaskStatus.RETRY_WAIT);
        assertThat(excelTask.getAttempts()).isEqualTo(1);
        assertThat(excelTask.getNextAttemptAt()).isAfter(java.time.Instant.now());
    }

    @Test
    void alertRetriesAppendAttemptRecordsInsteadOfOverwritingHistory() {
        properties.getDelivery().setMaxAttempts(2);
        doThrow(new IllegalStateException("temporary alert outage"))
                .when(alertNotifier)
                .notify(any(AlertRecord.class), any(PsychologicalReport.class), anyString());

        Long reportId = saveHighRiskReport().getId();

        toolOrchestrationService.handle(reportId);

        DeliveryTask alertTask = deliveryTaskRepository.findByReport_Id(reportId).stream()
                .filter(task -> task.getTaskType() == DeliveryTaskType.ALERT_NOTIFICATION)
                .findFirst()
                .orElseThrow();
        assertThat(notificationRecordRepository.findByDeliveryTask_IdOrderByAttemptNumberAsc(alertTask.getId()))
                .extracting(record -> record.getAttemptNumber(), record -> record.getStatus())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, NotificationAttemptStatus.FAILED));

        alertTask.setNextAttemptAt(java.time.Instant.now().minusSeconds(1));
        deliveryTaskRepository.saveAndFlush(alertTask);
        toolOrchestrationService.handle(reportId);

        assertThat(notificationRecordRepository.findByDeliveryTask_IdOrderByAttemptNumberAsc(alertTask.getId()))
                .extracting(record -> record.getAttemptNumber(), record -> record.getStatus())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, NotificationAttemptStatus.FAILED),
                        org.assertj.core.groups.Tuple.tuple(2, NotificationAttemptStatus.FAILED));
        assertThat(deliveryTaskRepository.findById(alertTask.getId()).orElseThrow().getStatus())
                .isEqualTo(DeliveryTaskStatus.FAILED);
    }

    @Test
    void expiredAlertLeaseArchivesUnknownAttemptBeforeRetrying() {
        properties.getDelivery().setMaxAttempts(2);
        Long reportId = saveHighRiskReport().getId();
        PsychologicalReport report = reportRepository.findById(reportId).orElseThrow();
        String recipient = "counselor@example.com";

        AlertRecord alertRecord = new AlertRecord();
        alertRecord.setReport(report);
        alertRecord.setRecipient(recipient);
        alertRecord = alertRecordRepository.saveAndFlush(alertRecord);

        DeliveryTask alertTask = new DeliveryTask();
        alertTask.setReport(report);
        alertTask.setAlertRecord(alertRecord);
        alertTask.setTaskType(DeliveryTaskType.ALERT_NOTIFICATION);
        alertTask.setRecipient(recipient);
        alertTask.setIdempotencyKey("alert:" + reportId + ":" + recipient);
        alertTask.incrementAttempts();
        alertTask.setStatus(DeliveryTaskStatus.PROCESSING);
        alertTask.setLeaseToken("expired-lease");
        alertTask.setLeaseUntil(java.time.Instant.now().minusSeconds(1));
        alertTask = deliveryTaskRepository.saveAndFlush(alertTask);
        notificationRecordRepository.saveAndFlush(
                NotificationRecord.started(alertTask, java.time.Instant.now().minusSeconds(2)));

        toolOrchestrationService.handle(reportId);

        assertThat(notificationRecordRepository.findByDeliveryTask_IdOrderByAttemptNumberAsc(alertTask.getId()))
                .extracting(record -> record.getAttemptNumber(), record -> record.getStatus())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, NotificationAttemptStatus.UNKNOWN),
                        org.assertj.core.groups.Tuple.tuple(2, NotificationAttemptStatus.SUCCESS));
    }

    private PsychologicalReport saveHighRiskReport() {
        UserAccount user = new UserAccount();
        user.setUsername("student-1");
        user.setPassword("password");
        user.setDisplayName("Student");
        user.setRoles(java.util.Set.of(UserRole.STUDENT.authority()));
        user = userAccountRepository.saveAndFlush(user);

        PsychologicalReport report = new PsychologicalReport();
        report.setUser(user);
        report.setContent("需要立即关注当前安全状态");
        report.setNeedsRag(true);
        report.setEmotion(EmotionLabel.HIGH_RISK);
        report.setEmotionScore(4.0);
        report.setRiskLevel(RiskLevel.HIGH);
        report.setConfidence(0.95);
        report.setSummary("存在高风险信号");
        return reportRepository.saveAndFlush(report);
    }

    @TestConfiguration
    static class TestConfig {

        @Bean(name = "mcpTaskExecutor")
        TaskExecutor mcpTaskExecutor() {
            return Runnable::run;
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }
    }
}
