package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.ToolStatus;
import com.multimodalAgent.agent.dto.OperationsRiskCount;
import java.util.Collection;
import java.util.List;
import java.time.Instant;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PsychologicalReportRepository extends JpaRepository<PsychologicalReport, Long> {

    @Query("""
            select new com.multimodalAgent.agent.dto.OperationsRiskCount(
                report.riskLevel, count(report.id))
            from PsychologicalReport report
            join StudentProfile profile on profile.user = report.user
            where report.createdAt >= :from and report.createdAt < :to
            group by report.riskLevel
            """)
    List<OperationsRiskCount> aggregateStudentReportsByRiskLevel(
            @Param("from") Instant from,
            @Param("to") Instant to
    );

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
