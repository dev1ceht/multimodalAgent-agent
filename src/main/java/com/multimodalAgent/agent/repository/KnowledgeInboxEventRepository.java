package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.KnowledgeInboxEvent;
import com.multimodalAgent.agent.domain.KnowledgeInboxStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeInboxEventRepository extends JpaRepository<KnowledgeInboxEvent, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from KnowledgeInboxEvent event where event.eventId = :id")
    Optional<KnowledgeInboxEvent> findByIdForUpdate(@Param("id") String id);

    List<KnowledgeInboxEvent> findByStatusOrderByCreatedAtAsc(KnowledgeInboxStatus status, Pageable pageable);

    List<KnowledgeInboxEvent> findByStatusAndLeaseUntilLessThanEqualOrderByCreatedAtAsc(
            KnowledgeInboxStatus status,
            Instant now,
            Pageable pageable);
}
