package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.KnowledgePublicationLock;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface KnowledgePublicationLockRepository extends JpaRepository<KnowledgePublicationLock, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select lock from KnowledgePublicationLock lock where lock.id = 1")
    Optional<KnowledgePublicationLock> findSingletonForUpdate();
}
