package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.StudentProfile;
import com.multimodalAgent.agent.domain.StudentStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StudentProfileRepository extends JpaRepository<StudentProfile, Long> {

    long countByStatus(StudentStatus status);

    @EntityGraph(attributePaths = {"user", "department", "major", "studentClass"})
    Optional<StudentProfile> findByUser_Id(Long userId);

    @EntityGraph(attributePaths = {"user", "department", "major", "studentClass"})
    List<StudentProfile> findAllByUser_IdIn(Collection<Long> userIds);

    @EntityGraph(attributePaths = {"user", "department", "major", "studentClass"})
    @Query("select profile from StudentProfile profile")
    List<StudentProfile> findAllWithOrganization();
}
