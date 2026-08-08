package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.ToolStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PsychologicalReportRepository extends JpaRepository<PsychologicalReport, Long> {

    @EntityGraph(attributePaths = {"user", "session"})
    List<PsychologicalReport> findTop50ByUser_IdOrderByCreatedAtDesc(Long userId);

    @EntityGraph(attributePaths = {"user", "session"})
    List<PsychologicalReport> findBySession_PublicIdOrderByCreatedAtDesc(String sessionId);

    @EntityGraph(attributePaths = {"user", "session"})
    List<PsychologicalReport> findTop100ByUser_IdInOrderByCreatedAtDesc(Collection<Long> userIds);

    @EntityGraph(attributePaths = {"user", "session"})
    List<PsychologicalReport> findTop100ByUser_IdInAndRiskLevelOrderByCreatedAtDesc(
            Collection<Long> userIds,
            RiskLevel riskLevel
    );

    @EntityGraph(attributePaths = {"user", "session"})
    List<PsychologicalReport> findTop100ByUser_IdInAndExcelStatusOrderByCreatedAtDesc(
            Collection<Long> userIds,
            ToolStatus excelStatus
    );

    @EntityGraph(attributePaths = {"user", "session"})
    List<PsychologicalReport> findTop100ByUser_IdInAndExcelStatusAndRiskLevelOrderByCreatedAtDesc(
            Collection<Long> userIds,
            ToolStatus excelStatus,
            RiskLevel riskLevel
    );
}
