package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.Major;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MajorRepository extends JpaRepository<Major, Long> {

    Optional<Major> findByDepartment_IdAndCode(Long departmentId, String code);
}
