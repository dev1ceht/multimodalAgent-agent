package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.domain.DeliveryTask;
import com.multimodalAgent.agent.domain.NotificationAttemptStatus;
import com.multimodalAgent.agent.domain.NotificationRecord;
import com.multimodalAgent.agent.repository.NotificationRecordRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * JPA implementation of the notification attempt recorder.
 */
@Component
public class JpaNotificationAttemptRecorder implements NotificationAttemptRecorder {

    private final NotificationRecordRepository repository;

    public JpaNotificationAttemptRecorder(NotificationRecordRepository repository) {
        this.repository = repository;
    }

    @Override
    public void recordStarted(DeliveryTask task) {
        repository.save(NotificationRecord.started(task, Instant.now()));
    }

    @Override
    public void recordSucceeded(DeliveryTask task) {
        NotificationRecord record = currentRecord(task);
        record.markSucceeded(Instant.now());
        repository.save(record);
    }

    @Override
    public void recordFailed(DeliveryTask task, String errorMessage) {
        NotificationRecord record = currentRecord(task);
        record.markFailed(errorMessage, Instant.now());
        repository.save(record);
    }

    @Override
    public void recordUnknown(DeliveryTask task, String reason) {
        Instant completedAt = Instant.now();
        Optional<NotificationRecord> current = repository.findByDeliveryTask_IdAndAttemptNumber(
                task.getId(), task.getAttempts());
        if (current.isEmpty()) {
            NotificationRecord record = NotificationRecord.started(task, completedAt);
            record.markUnknown(reason, completedAt);
            repository.save(record);
            return;
        }

        NotificationRecord record = current.get();
        if (record.getStatus() == NotificationAttemptStatus.PENDING) {
            record.markUnknown(reason, completedAt);
            repository.save(record);
        }
    }

    private NotificationRecord currentRecord(DeliveryTask task) {
        return repository.findByDeliveryTask_IdAndAttemptNumber(task.getId(), task.getAttempts())
                .orElseThrow(() -> new IllegalStateException(
                        "Notification record not found for delivery task " + task.getId()
                                + " attempt " + task.getAttempts()));
    }
}
