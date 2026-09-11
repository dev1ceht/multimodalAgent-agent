package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.KnowledgeBuildAttempt;
import com.multimodalAgent.agent.domain.KnowledgeBuildAttemptStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeBuildAttemptRepository extends JpaRepository<KnowledgeBuildAttempt, String> {

    Optional<KnowledgeBuildAttempt> findByCollectionName(String collectionName);

    List<KnowledgeBuildAttempt> findByTaskIdOrderByCreatedAtDesc(Long taskId);

    List<KnowledgeBuildAttempt> findByKnowledgeVersionIdAndStatus(
            Long knowledgeVersionId,
            KnowledgeBuildAttemptStatus status);
}
