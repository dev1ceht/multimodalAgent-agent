package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.AgentRunRecord;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRunRepository extends JpaRepository<AgentRunRecord, String> {

    List<AgentRunRecord> findByStatus(String status);

    int deleteByFinishedAtBefore(Instant cutoff);
}
