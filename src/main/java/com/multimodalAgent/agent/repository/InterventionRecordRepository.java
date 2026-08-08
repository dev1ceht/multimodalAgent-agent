package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.InterventionRecord;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterventionRecordRepository extends JpaRepository<InterventionRecord, Long> {

    long countByOccurredAtGreaterThanEqualAndOccurredAtLessThan(Instant from, Instant to);

    @EntityGraph(attributePaths = {"riskCase", "createdBy"})
    List<InterventionRecord> findByRiskCase_IdOrderByOccurredAtDesc(Long riskCaseId);
}
