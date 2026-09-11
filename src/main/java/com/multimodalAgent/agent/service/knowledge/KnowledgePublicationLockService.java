package com.multimodalAgent.agent.service.knowledge;

import com.multimodalAgent.agent.domain.KnowledgePublicationLock;
import com.multimodalAgent.agent.repository.KnowledgePublicationLockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Acquires the singleton database row used to serialize complete knowledge snapshots. */
@Service
public class KnowledgePublicationLockService {

    private final KnowledgePublicationLockRepository repository;

    public KnowledgePublicationLockService(KnowledgePublicationLockRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock() {
        KnowledgePublicationLock lock = repository.findSingletonForUpdate().orElseGet(() -> {
            KnowledgePublicationLock created = new KnowledgePublicationLock();
            return repository.saveAndFlush(created);
        });
        // Touching the managed singleton makes the lock visible in databases that only acquire
        // a write lock after an update while keeping the row's value semantically unchanged.
        if (lock.getVersion() < 0) {
            throw new IllegalStateException("Knowledge publication lock is invalid");
        }
    }
}
