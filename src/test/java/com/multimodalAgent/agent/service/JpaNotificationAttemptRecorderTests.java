package com.multimodalAgent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.domain.DeliveryTask;
import com.multimodalAgent.agent.domain.DeliveryTaskType;
import com.multimodalAgent.agent.domain.NotificationAttemptStatus;
import com.multimodalAgent.agent.domain.NotificationRecord;
import com.multimodalAgent.agent.repository.NotificationRecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JpaNotificationAttemptRecorderTests {

    @Mock
    private NotificationRecordRepository repository;

    @Mock
    private DeliveryTask task;

    @Mock
    private NotificationRecord record;

    @Test
    void recordsStartedAttemptAsPending() {
        DeliveryTask alertTask = alertTask();
        JpaNotificationAttemptRecorder recorder = new JpaNotificationAttemptRecorder(repository);

        recorder.recordStarted(alertTask);

        ArgumentCaptor<NotificationRecord> captor = ArgumentCaptor.forClass(NotificationRecord.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationAttemptStatus.PENDING);
        assertThat(captor.getValue().getAttemptNumber()).isEqualTo(1);
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo("alert:42:counselor@example.com");
    }

    @Test
    void marksTheCurrentAttemptSuccessful() {
        when(task.getId()).thenReturn(42L);
        when(task.getAttempts()).thenReturn(2);
        when(repository.findByDeliveryTask_IdAndAttemptNumber(42L, 2)).thenReturn(java.util.Optional.of(record));
        JpaNotificationAttemptRecorder recorder = new JpaNotificationAttemptRecorder(repository);

        recorder.recordSucceeded(task);

        verify(record).markSucceeded(any());
    }

    @Test
    void marksTheCurrentAttemptFailed() {
        when(task.getId()).thenReturn(42L);
        when(task.getAttempts()).thenReturn(2);
        when(repository.findByDeliveryTask_IdAndAttemptNumber(42L, 2)).thenReturn(java.util.Optional.of(record));
        JpaNotificationAttemptRecorder recorder = new JpaNotificationAttemptRecorder(repository);

        recorder.recordFailed(task, "SMTP timeout");

        verify(record).markFailed(eq("SMTP timeout"), any());
    }

    @Test
    void marksTheCurrentAttemptUnknownWhenItsLeaseExpires() {
        when(task.getId()).thenReturn(42L);
        when(task.getAttempts()).thenReturn(2);
        when(repository.findByDeliveryTask_IdAndAttemptNumber(42L, 2)).thenReturn(java.util.Optional.of(record));
        when(record.getStatus()).thenReturn(NotificationAttemptStatus.PENDING);
        JpaNotificationAttemptRecorder recorder = new JpaNotificationAttemptRecorder(repository);

        recorder.recordUnknown(task, "Delivery lease expired");

        verify(record).markUnknown(eq("Delivery lease expired"), any());
    }

    @Test
    void createsAnUnknownRecordWhenTheExpiredAttemptWasNotPersisted() {
        when(task.getId()).thenReturn(42L);
        when(task.getAttempts()).thenReturn(2);
        when(task.getTaskType()).thenReturn(DeliveryTaskType.ALERT_NOTIFICATION);
        when(task.getRecipient()).thenReturn("counselor@example.com");
        when(task.getIdempotencyKey()).thenReturn("alert:42:counselor@example.com");
        when(repository.findByDeliveryTask_IdAndAttemptNumber(42L, 2)).thenReturn(java.util.Optional.empty());
        JpaNotificationAttemptRecorder recorder = new JpaNotificationAttemptRecorder(repository);

        recorder.recordUnknown(task, "Delivery lease expired");

        ArgumentCaptor<NotificationRecord> captor = ArgumentCaptor.forClass(NotificationRecord.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationAttemptStatus.UNKNOWN);
        assertThat(captor.getValue().getAttemptNumber()).isEqualTo(2);
    }

    @Test
    void doesNotRewriteAnAttemptThatAlreadyHasAnOutcome() {
        when(task.getId()).thenReturn(42L);
        when(task.getAttempts()).thenReturn(2);
        when(repository.findByDeliveryTask_IdAndAttemptNumber(42L, 2)).thenReturn(java.util.Optional.of(record));
        when(record.getStatus()).thenReturn(NotificationAttemptStatus.SUCCESS);
        JpaNotificationAttemptRecorder recorder = new JpaNotificationAttemptRecorder(repository);

        recorder.recordUnknown(task, "Delivery lease expired");

        verify(record, never()).markUnknown(any(), any());
        verify(repository, never()).save(record);
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
