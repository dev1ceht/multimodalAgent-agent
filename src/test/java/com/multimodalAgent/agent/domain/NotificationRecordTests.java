package com.multimodalAgent.agent.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationRecordTests {

    private static final Instant STARTED_AT = Instant.parse("2026-08-04T02:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-04T02:00:03Z");

    @Test
    void startsAnAttemptWithStableDeliveryIdentityAndMaskedRecipient() {
        DeliveryTask task = alertTask();

        NotificationRecord record = NotificationRecord.started(task, STARTED_AT);

        assertThat(record.getDeliveryTask()).isSameAs(task);
        assertThat(record.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(record.getRecipientType()).isEqualTo(NotificationRecipientType.COUNSELOR);
        assertThat(record.getRecipientMasked()).isEqualTo("c***@example.com");
        assertThat(record.getStatus()).isEqualTo(NotificationAttemptStatus.PENDING);
        assertThat(record.getAttemptNumber()).isEqualTo(1);
        assertThat(record.getIdempotencyKey()).isEqualTo("alert:42:counselor@example.com");
        assertThat(record.getCreatedAt()).isEqualTo(STARTED_AT);
    }

    @Test
    void completesAnAttemptWithoutChangingLogicalDeliveryIdentity() {
        NotificationRecord record = NotificationRecord.started(alertTask(), STARTED_AT);

        record.markSucceeded(COMPLETED_AT);

        assertThat(record.getStatus()).isEqualTo(NotificationAttemptStatus.SUCCESS);
        assertThat(record.getCompletedAt()).isEqualTo(COMPLETED_AT);
        assertThat(record.getErrorMessage()).isNull();
        assertThat(record.getIdempotencyKey()).isEqualTo("alert:42:counselor@example.com");
    }

    @Test
    void failsAnAttemptWithARecoverableErrorDescription() {
        NotificationRecord record = NotificationRecord.started(alertTask(), STARTED_AT);

        record.markFailed("SMTP timeout", COMPLETED_AT);

        assertThat(record.getStatus()).isEqualTo(NotificationAttemptStatus.FAILED);
        assertThat(record.getCompletedAt()).isEqualTo(COMPLETED_AT);
        assertThat(record.getErrorMessage()).isEqualTo("SMTP timeout");
    }

    @Test
    void marksAnAttemptUnknownWhenTheOutcomeCannotBeObserved() {
        NotificationRecord record = NotificationRecord.started(alertTask(), STARTED_AT);

        record.markUnknown("Delivery lease expired", COMPLETED_AT);

        assertThat(record.getStatus()).isEqualTo(NotificationAttemptStatus.UNKNOWN);
        assertThat(record.getCompletedAt()).isEqualTo(COMPLETED_AT);
        assertThat(record.getErrorMessage()).isEqualTo("Delivery lease expired");
    }

    private DeliveryTask alertTask() {
        DeliveryTask task = new DeliveryTask();
        task.setTaskType(DeliveryTaskType.ALERT_NOTIFICATION);
        task.setRecipient("counselor@example.com");
        task.setIdempotencyKey("alert:42:counselor@example.com");
        task.incrementAttempts();
        return task;
    }
}
