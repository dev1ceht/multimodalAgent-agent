package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.ConsentRecord;
import com.multimodalAgent.agent.domain.ConsentStatus;
import com.multimodalAgent.agent.domain.ConsentType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, Long> {

    List<ConsentRecord> findByStudentUser_IdOrderByCreatedAtDesc(Long studentUserId);

    List<ConsentRecord> findByStudentUser_IdAndConsentTypeAndStatus(
            Long studentUserId,
            ConsentType consentType,
            ConsentStatus status
    );

    boolean existsByStudentUser_IdAndConsentTypeAndStatus(
            Long studentUserId,
            ConsentType consentType,
            ConsentStatus status
    );

    Optional<ConsentRecord> findTopByStudentUser_IdAndConsentTypeAndVersionAndStatusOrderByCreatedAtDesc(
            Long studentUserId,
            ConsentType consentType,
            String version,
            ConsentStatus status
    );
}
