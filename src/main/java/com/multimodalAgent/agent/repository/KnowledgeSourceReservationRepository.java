package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.KnowledgeSourceReservation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeSourceReservationRepository extends JpaRepository<KnowledgeSourceReservation, Long> {

    Optional<KnowledgeSourceReservation> findBySource(String source);

    Optional<KnowledgeSourceReservation> findByUploadId(String uploadId);

    void deleteByUploadId(String uploadId);
}
