package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.ClassGroup;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassGroupRepository extends JpaRepository<ClassGroup, Long> {

    Optional<ClassGroup> findByMajor_IdAndCode(Long majorId, String code);
}
