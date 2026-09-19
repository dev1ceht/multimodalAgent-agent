package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.ConversationContextSummary;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationContextSummaryRepository
        extends JpaRepository<ConversationContextSummary, Long> {

    Optional<ConversationContextSummary> findBySessionIdAndUserId(Long sessionId, Long userId);

    Optional<ConversationContextSummary> findBySessionId(Long sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select summary from ConversationContextSummary summary where summary.sessionId = :sessionId")
    Optional<ConversationContextSummary> findBySessionIdForUpdate(@Param("sessionId") Long sessionId);
}
