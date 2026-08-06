package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.KnowledgeIndexTask;
import com.multimodalAgent.agent.domain.KnowledgeIndexTaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeIndexTaskRepository extends JpaRepository<KnowledgeIndexTask, Long> {

    Optional<KnowledgeIndexTask> findByIdempotencyKey(String idempotencyKey);

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
