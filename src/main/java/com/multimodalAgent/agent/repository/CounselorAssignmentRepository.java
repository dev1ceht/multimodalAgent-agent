package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.CounselorAssignment;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CounselorAssignmentRepository extends JpaRepository<CounselorAssignment, Long> {

    @EntityGraph(attributePaths = {"department", "major", "studentClass"})
    List<CounselorAssignment> findByCounselor_IdAndEnabledTrue(Long counselorId);
}
