package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.KnowledgeVersionDocument;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeVersionDocumentRepository extends JpaRepository<KnowledgeVersionDocument, Long> {

    List<KnowledgeVersionDocument> findByKnowledgeVersionIdOrderBySourceAsc(Long knowledgeVersionId);
}
