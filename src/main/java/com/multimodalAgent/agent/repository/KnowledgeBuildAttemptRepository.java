package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.KnowledgeBuildAttempt;
import com.multimodalAgent.agent.domain.KnowledgeBuildAttemptStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeBuildAttemptRepository extends JpaRepository<KnowledgeBuildAttempt, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select attempt from KnowledgeBuildAttempt attempt "
            + "where attempt.buildAttemptId = :buildAttemptId")
    Optional<KnowledgeBuildAttempt> findByIdForUpdate(@Param("buildAttemptId") String buildAttemptId);

    Optional<KnowledgeBuildAttempt> findByCollectionName(String collectionName);

    List<KnowledgeBuildAttempt> findByTaskIdOrderByCreatedAtDesc(Long taskId);

    List<KnowledgeBuildAttempt> findByKnowledgeVersionIdAndStatus(
            Long knowledgeVersionId,
            KnowledgeBuildAttemptStatus status);

    List<KnowledgeBuildAttempt> findByStatusInAndCreatedAtLessThanEqualOrderByCreatedAtAsc(
            Collection<KnowledgeBuildAttemptStatus> statuses,
            Instant cutoff,
            Pageable pageable);
}
