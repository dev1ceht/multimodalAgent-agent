package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.MemoryTopic;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemoryTopicRepository extends JpaRepository<MemoryTopic, Long> {
    Optional<MemoryTopic> findByUserIdAndTopicKey(Long userId, String topicKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select topic from MemoryTopic topic where topic.userId = :userId and topic.topicKey = :topicKey")
    Optional<MemoryTopic> findByUserIdAndTopicKeyForUpdate(
            @Param("userId") Long userId, @Param("topicKey") String topicKey);

    List<MemoryTopic> findByUserId(Long userId);
}
