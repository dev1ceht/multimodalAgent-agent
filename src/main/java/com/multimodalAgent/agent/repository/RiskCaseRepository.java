package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.RiskCase;
import com.multimodalAgent.agent.domain.RiskLevel;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskCaseRepository extends JpaRepository<RiskCase, Long> {

    @EntityGraph(attributePaths = {"triggerReport", "studentUser"})
    Optional<RiskCase> findByTriggerReport_Id(Long reportId);

    @Override
    @EntityGraph(attributePaths = {"triggerReport", "studentUser"})
    Optional<RiskCase> findById(Long id);

    @EntityGraph(attributePaths = {"triggerReport", "studentUser"})
    List<RiskCase> findTop100ByRiskLevelOrderByUpdatedAtDesc(RiskLevel riskLevel);

    @EntityGraph(attributePaths = {"triggerReport", "studentUser"})
    List<RiskCase> findTop100ByStudentUser_IdInAndRiskLevelOrderByUpdatedAtDesc(
            Collection<Long> studentUserIds,
            RiskLevel riskLevel
    );

    @EntityGraph(attributePaths = {"triggerReport", "studentUser"})
    List<RiskCase> findTop100ByStudentUser_IdOrderByUpdatedAtDesc(Long studentUserId);
}
