package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.Referral;
import com.multimodalAgent.agent.domain.ReferralStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReferralRepository extends JpaRepository<Referral, Long> {

    long countByStatusIn(Collection<ReferralStatus> statuses);

    long countByDueAtBeforeAndStatusIn(java.time.Instant dueAt, Collection<ReferralStatus> statuses);

    @EntityGraph(attributePaths = {"riskCase", "createdBy", "targetUser"})
    List<Referral> findByRiskCase_IdOrderByCreatedAtDesc(Long riskCaseId);

    @EntityGraph(attributePaths = {"riskCase", "createdBy", "targetUser"})
    Optional<Referral> findByIdAndRiskCase_Id(Long id, Long riskCaseId);

    @EntityGraph(attributePaths = {"riskCase"})
    List<Referral> findByRiskCase_StudentUser_IdAndStatusIn(
            Long studentUserId,
            Collection<ReferralStatus> statuses
    );
}
