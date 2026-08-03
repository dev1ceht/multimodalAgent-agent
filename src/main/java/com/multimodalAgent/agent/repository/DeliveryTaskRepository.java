package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.DeliveryTask;
import com.multimodalAgent.agent.domain.DeliveryTaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 投递任务的持久化访问接口。
 */
public interface DeliveryTaskRepository extends JpaRepository<DeliveryTask, Long> {

    Optional<DeliveryTask> findByIdempotencyKey(String idempotencyKey);

    List<DeliveryTask> findByReport_Id(Long reportId);

    List<DeliveryTask> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            DeliveryTaskStatus status,
            Instant now,
            Pageable pageable
    );

    List<DeliveryTask> findByStatusAndLeaseUntilLessThanEqualOrderByCreatedAtAsc(
            DeliveryTaskStatus status,
            Instant now,
            Pageable pageable
    );

    List<DeliveryTask> findByReport_IdAndStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            Long reportId,
            DeliveryTaskStatus status,
            Instant now,
            Pageable pageable
    );

    List<DeliveryTask> findByReport_IdAndStatusAndLeaseUntilLessThanEqualOrderByCreatedAtAsc(
            Long reportId,
            DeliveryTaskStatus status,
            Instant now,
            Pageable pageable
    );

    @Query("""
            select distinct task
            from DeliveryTask task
            join fetch task.report report
            join fetch report.user
            left join fetch report.session
            left join fetch task.alertRecord
            where task.id = :taskId
            """)
    Optional<DeliveryTask> findDetailedById(@Param("taskId") Long taskId);
}
