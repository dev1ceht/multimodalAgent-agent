package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.KnowledgeEventType;
import com.multimodalAgent.agent.domain.KnowledgeOutboxEvent;
import com.multimodalAgent.agent.domain.KnowledgeOutboxStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeOutboxEventRepository extends JpaRepository<KnowledgeOutboxEvent, Long> {

    Optional<KnowledgeOutboxEvent> findByEventId(String eventId);

    Optional<KnowledgeOutboxEvent> findByEventTypeAndAggregateIdAndDispatchGeneration(
            KnowledgeEventType eventType,
            String aggregateId,
            long dispatchGeneration);

    List<KnowledgeOutboxEvent> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            KnowledgeOutboxStatus status,
            Instant now,
            Pageable pageable);

    List<KnowledgeOutboxEvent> findByStatusAndLeaseUntilLessThanEqualOrderByCreatedAtAsc(
            KnowledgeOutboxStatus status,
            Instant now,
            Pageable pageable);
}
