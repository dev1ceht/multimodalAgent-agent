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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;

/**
 * Durable history for one concrete high-risk notification attempt.
 */
@Entity
@Table(
        name = "notification_records",
        indexes = {
                @Index(name = "idx_notification_record_task", columnList = "delivery_task_id"),
                @Index(name = "idx_notification_record_status", columnList = "status,created_at"),
                @Index(name = "idx_notification_record_key", columnList = "idempotency_key")
        },
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notification_record_task_attempt",
                columnNames = {"delivery_task_id", "attempt_number"}))
public class NotificationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_task_id", nullable = false)
    private DeliveryTask deliveryTask;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", nullable = false, length = 32)
    private NotificationRecipientType recipientType;

    @Column(name = "recipient_masked", nullable = false, length = 240)
    private String recipientMasked;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationAttemptStatus status = NotificationAttemptStatus.PENDING;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "idempotency_key", nullable = false, length = 240)
    private String idempotencyKey;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected NotificationRecord() {
    }

    public static NotificationRecord started(DeliveryTask task, Instant startedAt) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(startedAt, "startedAt");
        if (task.getTaskType() != DeliveryTaskType.ALERT_NOTIFICATION) {
            throw new IllegalArgumentException("Notification records require an alert task");
        }
        if (task.getAttempts() <= 0) {
            throw new IllegalArgumentException("Notification record attempt number must be positive");
        }
        String recipient = requireText(task.getRecipient(), "recipient");
        String idempotencyKey = requireText(task.getIdempotencyKey(), "idempotencyKey");

        NotificationRecord record = new NotificationRecord();
        record.deliveryTask = task;
        record.channel = NotificationChannel.EMAIL;
        record.recipientType = NotificationRecipientType.COUNSELOR;
        record.recipientMasked = maskRecipient(recipient);
        record.status = NotificationAttemptStatus.PENDING;
        record.idempotencyKey = idempotencyKey;
        record.attemptNumber = task.getAttempts();
        record.createdAt = startedAt;
        record.updatedAt = startedAt;
        return record;
    }

    public void markSucceeded(Instant completedAt) {
        markCompleted(NotificationAttemptStatus.SUCCESS, null, completedAt);
    }

    public void markFailed(String errorMessage, Instant completedAt) {
        markCompleted(NotificationAttemptStatus.FAILED, requireText(errorMessage, "errorMessage"), completedAt);
    }

    public void markUnknown(String reason, Instant completedAt) {
        markCompleted(NotificationAttemptStatus.UNKNOWN, requireText(reason, "reason"), completedAt);
    }

    public Long getId() {
        return id;
    }

    public DeliveryTask getDeliveryTask() {
        return deliveryTask;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public NotificationRecipientType getRecipientType() {
        return recipientType;
    }

    public String getRecipientMasked() {
        return recipientMasked;
    }

    public NotificationAttemptStatus getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private void markCompleted(
            NotificationAttemptStatus completedStatus,
            String error,
            Instant completedAt
    ) {
        Objects.requireNonNull(completedAt, "completedAt");
        if (status != NotificationAttemptStatus.PENDING) {
            throw new IllegalStateException("Notification attempt is already completed");
        }
        status = completedStatus;
        errorMessage = error;
        this.completedAt = completedAt;
        updatedAt = completedAt;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String maskRecipient(String recipient) {
        int at = recipient.indexOf('@');
        if (at <= 0) {
            return recipient.charAt(0) + "***";
        }
        return recipient.charAt(0) + "***" + recipient.substring(at);
    }
}
