package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.KnowledgeVersionChunk;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeVersionChunkRepository extends JpaRepository<KnowledgeVersionChunk, Long> {

    List<KnowledgeVersionChunk> findByKnowledgeVersionIdOrderBySourceAscSourceIndexAsc(Long knowledgeVersionId);

    List<KnowledgeVersionChunk> findByKnowledgeVersionIdAndSourceAndSourceIndexBetweenOrderBySourceIndexAsc(
            Long knowledgeVersionId,
            String source,
            int startIndex,
            int endIndex
    );

    long countByKnowledgeVersionId(Long knowledgeVersionId);

    void deleteByKnowledgeVersionId(Long knowledgeVersionId);
}
