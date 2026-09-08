package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.MemoryTopic;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryTopicRepository extends JpaRepository<MemoryTopic, Long> {
    Optional<MemoryTopic> findByUserIdAndTopicKey(Long userId, String topicKey);
    List<MemoryTopic> findByUserId(Long userId);
}
