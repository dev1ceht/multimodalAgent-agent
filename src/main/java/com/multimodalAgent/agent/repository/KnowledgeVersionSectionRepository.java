package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.KnowledgeVersionSection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeVersionSectionRepository extends JpaRepository<KnowledgeVersionSection, Long> {
    List<KnowledgeVersionSection> findByKnowledgeVersionIdOrderBySourceAscSectionIndexAsc(Long versionId);
    List<KnowledgeVersionSection> findByKnowledgeVersionIdAndBuildAttemptIdOrderBySourceAscSectionIndexAsc(
            Long versionId,
            String buildAttemptId);
    void deleteByKnowledgeVersionId(Long versionId);
    void deleteByKnowledgeVersionIdAndBuildAttemptId(Long versionId, String buildAttemptId);
}
