package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.KnowledgeDocument;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    Optional<KnowledgeDocument> findBySource(String source);

    List<KnowledgeDocument> findAllByOrderBySourceAsc();
}
