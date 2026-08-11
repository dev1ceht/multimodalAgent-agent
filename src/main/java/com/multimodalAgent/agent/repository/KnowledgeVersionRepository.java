package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.KnowledgeVersion;
import com.multimodalAgent.agent.domain.KnowledgeVersionStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeVersionRepository extends JpaRepository<KnowledgeVersion, Long> {

    Optional<KnowledgeVersion> findByVersionKey(String versionKey);

    Optional<KnowledgeVersion> findTopByStatusOrderByActivatedAtDesc(KnowledgeVersionStatus status);

    Optional<KnowledgeVersion> findTopByOrderByCreatedAtDescIdDesc();

    List<KnowledgeVersion> findTop20ByOrderByCreatedAtDescIdDesc();

    List<KnowledgeVersion> findByStatus(KnowledgeVersionStatus status);
}
