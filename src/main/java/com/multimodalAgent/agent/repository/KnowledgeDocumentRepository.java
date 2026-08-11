package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.KnowledgeDocument;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    Optional<KnowledgeDocument> findBySource(String source);

    List<KnowledgeDocument> findAllByOrderBySourceAsc();

    @Query(
            value = """
                    SELECT id,
                           source,
                           CHAR_LENGTH(content) AS characterCount,
                           SUBSTRING(content, 1, 160) AS preview,
                           version
                    FROM knowledge_documents
                    """,
            countQuery = "SELECT COUNT(*) FROM knowledge_documents",
            nativeQuery = true)
    Page<KnowledgeDocumentListView> findDocumentSummaries(Pageable pageable);

    interface KnowledgeDocumentListView {

        Long getId();

        String getSource();

        Integer getCharacterCount();

        String getPreview();

        Long getVersion();
    }
}
