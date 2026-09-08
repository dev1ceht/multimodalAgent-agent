package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.MemoryFactTopic;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryFactTopicRepository extends JpaRepository<MemoryFactTopic, Long> {
    boolean existsByFactIdAndTopicId(Long factId, Long topicId);
    List<MemoryFactTopic> findByTopicIdIn(List<Long> topicIds);
}
