package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.KnowledgeUpload;
import com.multimodalAgent.agent.domain.KnowledgeUploadStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeUploadRepository extends JpaRepository<KnowledgeUpload, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select upload from KnowledgeUpload upload where upload.id = :id")
    Optional<KnowledgeUpload> findByIdForUpdate(@Param("id") String id);

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
