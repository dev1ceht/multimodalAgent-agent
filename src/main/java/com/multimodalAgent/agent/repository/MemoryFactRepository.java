package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.MemoryFact;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryFactRepository extends JpaRepository<MemoryFact, Long> {
    boolean existsBySourceMessageId(Long sourceMessageId);
    List<MemoryFact> findBySourceMessageIdOrderByFactOrdinalAsc(Long sourceMessageId);
    List<MemoryFact> findByUserIdOrderByIdAsc(Long userId);
    List<MemoryFact> findByUserIdAndIdGreaterThanOrderByIdAsc(Long userId, Long id);
    List<MemoryFact> findByUserIdOrderByOccurredAtDesc(Long userId, Pageable pageable);
    List<MemoryFact> findByUserIdAndSessionIdOrderByOccurredAtAsc(Long userId, Long sessionId);
}
