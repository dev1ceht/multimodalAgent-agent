package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.AgentToolExecutionRecord;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentToolExecutionRepository extends JpaRepository<AgentToolExecutionRecord, Long> {

    Optional<AgentToolExecutionRecord> findFirstByRunIdAndFinishedAtIsNullOrderByStartedAtDesc(
            String runId);

    int deleteByStartedAtBefore(Instant cutoff);
}
