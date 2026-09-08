package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.MemoryRelation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryRelationRepository extends JpaRepository<MemoryRelation, Long> {
    boolean existsBySourceFactIdAndTargetFactIdAndType(Long source, Long target, com.multimodalAgent.agent.service.memory.MemoryRelationType type);
}
