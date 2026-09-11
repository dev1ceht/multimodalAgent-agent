package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.KnowledgeIndexTask;
import com.multimodalAgent.agent.domain.KnowledgeIndexTaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeIndexTaskRepository extends JpaRepository<KnowledgeIndexTask, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from KnowledgeIndexTask task where task.id = :id")
    Optional<KnowledgeIndexTask> findByIdForUpdate(@Param("id") Long id);

    Optional<KnowledgeIndexTask> findByIdempotencyKey(String idempotencyKey);

    Optional<KnowledgeIndexTask> findTopByKnowledgeVersionIdOrderByCreatedAtDesc(Long knowledgeVersionId);

    List<KnowledgeIndexTask> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            KnowledgeIndexTaskStatus status,
            Instant now,
            Pageable pageable
    );

    List<KnowledgeIndexTask> findByStatusAndLeaseUntilLessThanEqualOrderByCreatedAtAsc(
            KnowledgeIndexTaskStatus status,
            Instant now,
            Pageable pageable
    );
}
