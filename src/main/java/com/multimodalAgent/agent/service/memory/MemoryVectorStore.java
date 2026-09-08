package com.multimodalAgent.agent.service.memory;

import java.util.List;

public interface MemoryVectorStore {
    void upsert(MemoryProjectionBatch batch);
    default void upsert(MemoryProjectionBatch batch, Runnable leaseGuard) {
        leaseGuard.run();
        upsert(batch);
    }
    List<MemoryVectorHit> searchFacts(Long userId, List<Double> vector, int limit);
    List<MemoryVectorHit> searchTopics(Long userId, List<Double> vector, int limit);
}
