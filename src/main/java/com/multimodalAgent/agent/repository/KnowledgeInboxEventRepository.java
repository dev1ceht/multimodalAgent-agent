package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.KnowledgeInboxEvent;
import com.multimodalAgent.agent.domain.KnowledgeInboxStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeInboxEventRepository extends JpaRepository<KnowledgeInboxEvent, String> {

    List<KnowledgeInboxEvent> findByStatusOrderByCreatedAtAsc(KnowledgeInboxStatus status, Pageable pageable);

    List<KnowledgeInboxEvent> findByStatusAndLeaseUntilLessThanEqualOrderByCreatedAtAsc(
            KnowledgeInboxStatus status,
            Instant now,
            Pageable pageable);
}
