package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.ConversationContextJob;
import com.multimodalAgent.agent.service.context.ContextJobStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationContextJobRepository extends JpaRepository<ConversationContextJob, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from ConversationContextJob job where job.sessionId = :sessionId")
    Optional<ConversationContextJob> findBySessionIdForUpdate(@Param("sessionId") Long sessionId);

    List<ConversationContextJob> findByStatusAndNextAttemptAtLessThanEqualOrderByUpdatedAtAsc(
            ContextJobStatus status, Instant now, Pageable pageable);

    List<ConversationContextJob> findByStatusAndLeaseUntilLessThanEqualOrderByUpdatedAtAsc(
            ContextJobStatus status, Instant now, Pageable pageable);

    long countByStatusAndNextAttemptAtLessThanEqual(ContextJobStatus status, Instant now);

    long countByStatusAndLeaseUntilLessThanEqual(ContextJobStatus status, Instant now);
}
