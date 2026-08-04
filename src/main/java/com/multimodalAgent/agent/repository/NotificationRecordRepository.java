package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.NotificationRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence interface for notification attempt history.
 */
public interface NotificationRecordRepository extends JpaRepository<NotificationRecord, Long> {

    Optional<NotificationRecord> findByDeliveryTask_IdAndAttemptNumber(Long taskId, int attemptNumber);

    List<NotificationRecord> findByDeliveryTask_IdOrderByAttemptNumberAsc(Long taskId);
}
