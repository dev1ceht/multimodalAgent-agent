package com.multimodalAgent.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Excel 导出和告警通知共用的持久化投递任务。
 *
 * <p>报告只负责产生任务，外部工具调用由任务状态驱动。这样外部调用失败、进程重启或
 * 调用方断开连接时，任务仍然可以被重新领取。</p>
 */
@Entity
@Table(
        name = "delivery_tasks",
        indexes = {
                @Index(name = "idx_delivery_task_due", columnList = "status,next_attempt_at"),
                @Index(name = "idx_delivery_task_lease", columnList = "status,lease_until"),
                @Index(name = "idx_delivery_task_report", columnList = "report_id")
        })
public class DeliveryTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private PsychologicalReport report;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "alert_record_id", unique = true)
    private AlertRecord alertRecord;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 32)
    private DeliveryTaskType taskType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryTaskStatus status = DeliveryTaskStatus.PENDING;

    @Column(length = 240)
    private String recipient;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 240)
    private String idempotencyKey;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "lease_token", length = 64)
    private String leaseToken;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    public Long getId() {
        return id;
    }

    public PsychologicalReport getReport() {
        return report;
    }

    public void setReport(PsychologicalReport report) {
        this.report = report;
    }

    public AlertRecord getAlertRecord() {
        return alertRecord;
    }

    public void setAlertRecord(AlertRecord alertRecord) {
        this.alertRecord = alertRecord;
    }

    public DeliveryTaskType getTaskType() {
        return taskType;
    }

    public void setTaskType(DeliveryTaskType taskType) {
        this.taskType = taskType;
    }

    public DeliveryTaskStatus getStatus() {
        return status;
    }

    public void setStatus(DeliveryTaskStatus status) {
        this.status = status;
        touch();
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public int getAttempts() {
        return attempts;
    }

    public void incrementAttempts() {
        attempts++;
        touch();
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
        touch();
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(Instant leaseUntil) {
        this.leaseUntil = leaseUntil;
        touch();
    }

    public String getLeaseToken() {
        return leaseToken;
    }

    public void setLeaseToken(String leaseToken) {
        this.leaseToken = leaseToken;
        touch();
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
        touch();
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
        touch();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private void touch() {
        updatedAt = Instant.now();
    }
}
