package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.config.RiskCaseSlaProperties;
import com.multimodalAgent.agent.domain.AlertRecord;
import com.multimodalAgent.agent.domain.DeliveryTask;
import com.multimodalAgent.agent.domain.DeliveryTaskStatus;
import com.multimodalAgent.agent.domain.DeliveryTaskType;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.ToolStatus;
import com.multimodalAgent.agent.repository.AlertRecordRepository;
import com.multimodalAgent.agent.repository.DeliveryTaskRepository;
import com.multimodalAgent.agent.repository.PsychologicalReportRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 后台投递任务编排服务。
 *
 * <p>一份心理报告会产生独立的 Excel 导出任务和高风险告警任务。任务先在数据库中落库，
 * 再由后台领取并调用外部工具；外部工具调用不运行在数据库事务内。</p>
 */
@Service
public class ToolOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(ToolOrchestrationService.class);

    private final DeliveryTaskExecutor deliveryTaskExecutor;
    private final NotificationAttemptRecorder notificationAttemptRecorder;
    private final PsychologicalReportRepository reportRepository;
    private final AlertRecordRepository alertRecordRepository;
    private final DeliveryTaskRepository deliveryTaskRepository;
    private final RiskCaseService riskCaseService;
    private final multimodalAgentProperties properties;
    private final RiskCaseSlaProperties riskCaseSlaProperties;
    private final TaskExecutor mcpTaskExecutor;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean drainRunning = new AtomicBoolean();

    public ToolOrchestrationService(
            DeliveryTaskExecutor deliveryTaskExecutor,
            NotificationAttemptRecorder notificationAttemptRecorder,
            PsychologicalReportRepository reportRepository,
            AlertRecordRepository alertRecordRepository,
            DeliveryTaskRepository deliveryTaskRepository,
            RiskCaseService riskCaseService,
            multimodalAgentProperties properties,
            RiskCaseSlaProperties riskCaseSlaProperties,
            @Qualifier("mcpTaskExecutor")
            TaskExecutor mcpTaskExecutor,
            TransactionTemplate transactionTemplate
    ) {
        this.deliveryTaskExecutor = deliveryTaskExecutor;
        this.notificationAttemptRecorder = notificationAttemptRecorder;
        this.reportRepository = reportRepository;
        this.alertRecordRepository = alertRecordRepository;
        this.deliveryTaskRepository = deliveryTaskRepository;
        this.riskCaseService = riskCaseService;
        this.properties = properties;
        this.riskCaseSlaProperties = riskCaseSlaProperties;
        this.mcpTaskExecutor = mcpTaskExecutor;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 持久化任务后立即触发一次后台拉取；即使进程在触发后退出，定时轮询也能继续处理任务。
     */
    public void handleAsync(Long reportId) {
        enqueueTasks(reportId);
        triggerDrain();
    }

    /**
     * 在同一个短事务内保存报告并创建投递任务，避免报告已落库但任务尚未生成的窗口。
     */
    public PsychologicalReport saveReportAndEnqueue(PsychologicalReport report) {
        PsychologicalReport saved = transactionTemplate.execute(status -> {
            PsychologicalReport managedReport = reportRepository.save(report);
            enqueueTasksInTransaction(managedReport);
            return managedReport;
        });
        triggerDrain();
        return saved;
    }

    /**
     * 同步入口主要供后台补偿和测试使用，仍然遵守“先落库、后调用外部工具”的规则。
     */
    public void handle(Long reportId) {
        enqueueTasks(reportId);
        processDueTasksForReport(reportId);
    }

    @Scheduled(fixedDelayString = "${multimodal-agent.delivery.poll-interval-ms:1000}")
    public void pollDueTasks() {
        triggerDrain();
    }

    private void enqueueTasks(Long reportId) {
        transactionTemplate.executeWithoutResult(status -> {
            PsychologicalReport report = reportRepository.findById(reportId)
                    .orElseThrow(() -> new IllegalArgumentException("Report not found: " + reportId));

            enqueueTasksInTransaction(report);
        });
    }

    private void enqueueTasksInTransaction(PsychologicalReport report) {

        riskCaseService.ensureCaseForReport(report);
        ensureExcelTask(report);
        if (riskCaseSlaProperties.notifiesStaff(report.getRiskLevel())) {
            List<String> recipients = configuredRecipients();
            if (recipients.isEmpty()) {
                report.setEmailStatus(ToolStatus.FAILED);
                report.setToolError("No alert recipients configured");
            } else {
                for (String recipient : recipients) {
                    ensureAlertTask(report, recipient);
                }
            }
        } else {
            report.setEmailStatus(ToolStatus.SKIPPED);
        }

        refreshReportStatus(report);
        reportRepository.save(report);
    }

    private void ensureExcelTask(PsychologicalReport report) {
        String idempotencyKey = "excel:" + report.getId();
        if (deliveryTaskRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            return;
        }

        DeliveryTask task = new DeliveryTask();
        task.setReport(report);
        task.setTaskType(DeliveryTaskType.EXCEL_EXPORT);
        task.setIdempotencyKey(idempotencyKey);
        task.setNextAttemptAt(Instant.now());
        deliveryTaskRepository.save(task);
    }

    private void ensureAlertTask(PsychologicalReport report, String recipient) {
        String idempotencyKey = "alert:" + report.getId() + ":" + recipient;
        if (deliveryTaskRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            return;
        }

        AlertRecord alertRecord = alertRecordRepository
                .findTopByReport_IdAndRecipientOrderByCreatedAtDesc(report.getId(), recipient)
                .orElseGet(() -> {
                    AlertRecord created = new AlertRecord();
                    created.setReport(report);
                    created.setRecipient(recipient);
                    return alertRecordRepository.saveAndFlush(created);
                });

        DeliveryTask task = new DeliveryTask();
        task.setReport(report);
        task.setAlertRecord(alertRecord);
        task.setTaskType(DeliveryTaskType.ALERT_NOTIFICATION);
        task.setRecipient(recipient);
        task.setIdempotencyKey(idempotencyKey);
        task.setNextAttemptAt(Instant.now());
        if (alertRecord.getStatus() == ToolStatus.SUCCESS) {
            task.setStatus(DeliveryTaskStatus.SUCCEEDED);
            task.setCompletedAt(Instant.now());
        }
        deliveryTaskRepository.save(task);
    }

    private List<String> configuredRecipients() {
        return properties.getMcp().getEmail().getRecipients().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(recipient -> !recipient.isBlank())
                .distinct()
                .toList();
    }

    private void triggerDrain() {
        if (!drainRunning.compareAndSet(false, true)) {
            return;
        }

        try {
            mcpTaskExecutor.execute(() -> {
                try {
                    drainAvailableTasks();
                } finally {
                    drainRunning.set(false);
                }
            });
        } catch (RuntimeException exception) {
            drainRunning.set(false);
            throw exception;
        }
    }

    private void drainAvailableTasks() {
        int submitted = 0;
        int batchSize = Math.max(1, properties.getDelivery().getBatchSize());
        while (submitted < batchSize) {
            DeliveryTaskClaim claim = claimNextTask();
            if (claim == null) {
                return;
            }
            try {
                mcpTaskExecutor.execute(() -> processClaimedTask(claim));
            } catch (RuntimeException exception) {
                failTask(claim, exception);
            }
            submitted++;
        }
    }

    private void processDueTasksForReport(Long reportId) {
        while (true) {
            DeliveryTaskClaim claim = claimNextTaskForReport(reportId);
            if (claim == null) {
                return;
            }
            processClaimedTask(claim);
        }
    }

    private DeliveryTaskClaim claimNextTask() {
        try {
            return transactionTemplate.execute(status -> {
                DeliveryTask task = selectDueTask(null, Instant.now());
                if (task == null) {
                    return null;
                }
                return new DeliveryTaskClaim(task.getId(), claim(task));
            });
        } catch (OptimisticLockingFailureException exception) {
            // 另一线程已经领取了候选任务，本轮退出，下一次轮询会继续处理剩余任务。
            return null;
        }
    }

    private DeliveryTaskClaim claimNextTaskForReport(Long reportId) {
        try {
            return transactionTemplate.execute(status -> {
                DeliveryTask task = selectDueTask(reportId, Instant.now());
                if (task == null) {
                    return null;
                }
                return new DeliveryTaskClaim(task.getId(), claim(task));
            });
        } catch (OptimisticLockingFailureException exception) {
            return null;
        }
    }

    private DeliveryTask selectDueTask(Long reportId, Instant now) {
        PageRequest page = PageRequest.of(0, Math.max(1, properties.getDelivery().getBatchSize()));
        List<DeliveryTask> candidates = new ArrayList<>();
        for (DeliveryTaskStatus status : List.of(DeliveryTaskStatus.PENDING, DeliveryTaskStatus.RETRY_WAIT)) {
            if (reportId == null) {
                candidates.addAll(deliveryTaskRepository
                        .findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(status, now, page));
            } else {
                candidates.addAll(deliveryTaskRepository
                        .findByReport_IdAndStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                                reportId, status, now, page));
            }
        }

        if (reportId == null) {
            candidates.addAll(deliveryTaskRepository
                    .findByStatusAndLeaseUntilLessThanEqualOrderByCreatedAtAsc(
                            DeliveryTaskStatus.PROCESSING, now, page));
        } else {
            candidates.addAll(deliveryTaskRepository
                    .findByReport_IdAndStatusAndLeaseUntilLessThanEqualOrderByCreatedAtAsc(
                            reportId, DeliveryTaskStatus.PROCESSING, now, page));
        }

        return candidates.stream()
                .min(Comparator.comparing(DeliveryTask::getCreatedAt))
                .orElse(null);
    }

    private String claim(DeliveryTask task) {
        Instant now = Instant.now();
        boolean expiredLease = task.getStatus() == DeliveryTaskStatus.PROCESSING
                && task.getLeaseUntil() != null
                && !task.getLeaseUntil().isAfter(now);
        if (expiredLease && isNotificationTask(task)) {
            notificationAttemptRecorder.recordUnknown(
                    task,
                    "Delivery lease expired before attempt completed");
        }
        String leaseToken = UUID.randomUUID().toString();
        task.incrementAttempts();
        task.setStatus(DeliveryTaskStatus.PROCESSING);
        task.setNextAttemptAt(now);
        task.setLeaseUntil(now.plusSeconds(Math.max(1, properties.getDelivery().getLeaseSeconds())));
        task.setLeaseToken(leaseToken);
        if (task.getAlertRecord() != null) {
            task.getAlertRecord().incrementAttempts();
            alertRecordRepository.save(task.getAlertRecord());
        }
        deliveryTaskRepository.saveAndFlush(task);
        if (isNotificationTask(task)) {
            notificationAttemptRecorder.recordStarted(task);
        }
        return leaseToken;
    }

    private void processClaimedTask(DeliveryTaskClaim claim) {
        Long taskId = claim.taskId();
        DeliveryTask task = deliveryTaskRepository.findDetailedById(taskId).orElse(null);
        if (!ownsLease(task, claim)) {
            return;
        }

        try {
            deliveryTaskExecutor.execute(task);
            completeTask(claim);
        } catch (Exception exception) {
            failTask(claim, exception);
        }
    }

    private void completeTask(DeliveryTaskClaim claim) {
        transactionTemplate.executeWithoutResult(status -> {
            DeliveryTask task = deliveryTaskRepository.findById(claim.taskId()).orElse(null);
            if (!ownsLease(task, claim)) {
                return;
            }

            task.setStatus(DeliveryTaskStatus.SUCCEEDED);
            task.setLeaseUntil(null);
            task.setLeaseToken(null);
            task.setCompletedAt(Instant.now());
            task.setLastError(null);
            if (isNotificationTask(task)) {
                notificationAttemptRecorder.recordSucceeded(task);
            }
            if (task.getAlertRecord() != null) {
                task.getAlertRecord().setStatus(ToolStatus.SUCCESS);
                task.getAlertRecord().setErrorMessage(null);
                alertRecordRepository.save(task.getAlertRecord());
            }
            refreshReportStatus(task.getReport());
            deliveryTaskRepository.save(task);
            reportRepository.save(task.getReport());
        });
    }

    private void failTask(DeliveryTaskClaim claim, Exception exception) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                DeliveryTask task = deliveryTaskRepository.findById(claim.taskId()).orElse(null);
                if (!ownsLease(task, claim)) {
                    return;
                }

                String error = shorten(exception);
                task.setLastError(error);
                boolean terminal = task.getAttempts() >= maxAttempts();
                if (terminal) {
                    task.setStatus(DeliveryTaskStatus.FAILED);
                    task.setLeaseUntil(null);
                    task.setLeaseToken(null);
                    task.setCompletedAt(Instant.now());
                } else {
                    task.setStatus(DeliveryTaskStatus.RETRY_WAIT);
                    task.setLeaseUntil(null);
                    task.setLeaseToken(null);
                    task.setNextAttemptAt(Instant.now().plusSeconds(retryDelaySeconds(task.getAttempts())));
                }

                if (isNotificationTask(task)) {
                    notificationAttemptRecorder.recordFailed(task, error);
                }
                if (task.getAlertRecord() != null) {
                    task.getAlertRecord().setStatus(terminal ? ToolStatus.FAILED : ToolStatus.PENDING);
                    task.getAlertRecord().setErrorMessage(error);
                    alertRecordRepository.save(task.getAlertRecord());
                }
                refreshReportStatus(task.getReport());
                deliveryTaskRepository.save(task);
                reportRepository.save(task.getReport());
            });
        } catch (RuntimeException updateException) {
            log.error("Failed to persist delivery task failure, taskId={}", claim.taskId(), updateException);
        }
    }

    private boolean ownsLease(DeliveryTask task, DeliveryTaskClaim claim) {
        return task != null
                && task.getStatus() == DeliveryTaskStatus.PROCESSING
                && Objects.equals(task.getLeaseToken(), claim.leaseToken());
    }

    private void refreshReportStatus(PsychologicalReport report) {
        List<DeliveryTask> tasks = deliveryTaskRepository.findByReport_Id(report.getId());
        tasks.stream()
                .filter(task -> task.getTaskType() == DeliveryTaskType.EXCEL_EXPORT)
                .findFirst()
                .ifPresent(task -> report.setExcelStatus(toToolStatus(task.getStatus())));

        List<DeliveryTask> alerts = tasks.stream()
                .filter(task -> task.getTaskType() == DeliveryTaskType.ALERT_NOTIFICATION)
                .toList();
        if (!riskCaseSlaProperties.notifiesStaff(report.getRiskLevel())) {
            report.setEmailStatus(ToolStatus.SKIPPED);
        } else if (alerts.isEmpty()) {
            report.setEmailStatus(ToolStatus.FAILED);
        } else if (alerts.stream().allMatch(task -> task.getStatus() == DeliveryTaskStatus.SUCCEEDED)) {
            report.setEmailStatus(ToolStatus.SUCCESS);
        } else if (alerts.stream().anyMatch(task -> task.getStatus() == DeliveryTaskStatus.FAILED)) {
            report.setEmailStatus(ToolStatus.FAILED);
        } else {
            report.setEmailStatus(ToolStatus.PENDING);
        }

        tasks.stream()
                .filter(task -> task.getStatus() == DeliveryTaskStatus.FAILED
                        || task.getStatus() == DeliveryTaskStatus.RETRY_WAIT)
                .map(DeliveryTask::getLastError)
                .filter(Objects::nonNull)
                .findFirst()
                .ifPresentOrElse(report::setToolError, () -> report.setToolError(null));
    }

    private ToolStatus toToolStatus(DeliveryTaskStatus status) {
        return switch (status) {
            case SUCCEEDED -> ToolStatus.SUCCESS;
            case FAILED -> ToolStatus.FAILED;
            case PENDING, PROCESSING, RETRY_WAIT -> ToolStatus.PENDING;
        };
    }

    private boolean isNotificationTask(DeliveryTask task) {
        return task.getTaskType() == DeliveryTaskType.ALERT_NOTIFICATION
                || task.getTaskType() == DeliveryTaskType.RISK_CASE_ESCALATION;
    }

    private int maxAttempts() {
        int configured = properties.getDelivery().getMaxAttempts();
        if (configured > 0) {
            return configured;
        }
        return Math.max(1, properties.getMcp().getEmail().getMaxRetries() + 1);
    }

    private long retryDelaySeconds(int attempts) {
        long base = Math.max(1, properties.getDelivery().getBaseRetryDelaySeconds());
        int exponent = Math.min(10, Math.max(0, attempts - 1));
        return Math.min(3600, base * (1L << exponent));
    }

    private record DeliveryTaskClaim(Long taskId, String leaseToken) {
    }

    private String shorten(Exception exception) {
        String message = exception.getMessage();
        String value = message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
