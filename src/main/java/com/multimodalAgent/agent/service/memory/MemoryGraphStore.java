package com.multimodalAgent.agent.service.memory;

import java.util.List;

public interface MemoryGraphStore {
    void upsert(MemoryProjectionBatch batch);
    default void upsert(MemoryProjectionBatch batch, Runnable leaseGuard) {
        leaseGuard.run();
        upsert(batch);
    }
    List<MemoryGraphHit> expand(Long userId, List<Long> seedFactIds, int maxHops);
}
