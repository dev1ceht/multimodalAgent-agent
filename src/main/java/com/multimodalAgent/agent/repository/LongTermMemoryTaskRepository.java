package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.LongTermMemoryTask;
import com.multimodalAgent.agent.service.memory.MemoryTaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface LongTermMemoryTaskRepository extends JpaRepository<LongTermMemoryTask, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from LongTermMemoryTask task where task.id = :id")
    Optional<LongTermMemoryTask> findByIdForUpdate(@Param("id") String id);

    Optional<LongTermMemoryTask> findBySourceMessageId(Long sourceMessageId);

    List<LongTermMemoryTask> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            MemoryTaskStatus status,
            Instant now,
            Pageable pageable);

    List<LongTermMemoryTask> findByStatusAndLeaseUntilLessThanEqualOrderByCreatedAtAsc(
            MemoryTaskStatus status,
            Instant now,
            Pageable pageable);
}
