package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.AlertRecord;
import com.multimodalAgent.agent.domain.RiskLevel;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRecordRepository extends JpaRepository<AlertRecord, Long> {

    List<AlertRecord> findByReport_Id(Long reportId);

    Optional<AlertRecord> findTopByReport_IdAndRecipientOrderByCreatedAtDesc(
            Long reportId,
            String recipient
    );

    @EntityGraph(attributePaths = {"report", "report.user", "report.session"})
    List<AlertRecord> findTop100ByReport_User_IdInOrderByCreatedAtDesc(Collection<Long> userIds);

    @EntityGraph(attributePaths = {"report", "report.user", "report.session"})
    List<AlertRecord> findTop100ByReport_User_IdInAndReport_RiskLevelOrderByCreatedAtDesc(
            Collection<Long> userIds,
            RiskLevel riskLevel
    );
}
