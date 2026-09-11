package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.KnowledgeUpload;
import com.multimodalAgent.agent.domain.KnowledgeUploadStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeUploadRepository extends JpaRepository<KnowledgeUpload, String> {

    Optional<KnowledgeUpload> findByUploadedByAndClientIdempotencyKey(
            Long uploadedBy,
            String clientIdempotencyKey);

    Page<KnowledgeUpload> findByUploadedByOrderByCreatedAtDesc(Long uploadedBy, Pageable pageable);

    List<KnowledgeUpload> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            KnowledgeUploadStatus status,
            Instant now,
            Pageable pageable);

    List<KnowledgeUpload> findByStatusAndLeaseUntilLessThanEqualOrderByCreatedAtAsc(
            KnowledgeUploadStatus status,
            Instant now,
            Pageable pageable);
}
