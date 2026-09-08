package com.multimodalAgent.agent.service.memory;

import java.util.List;

public interface MemoryGraphStore {
    void upsert(MemoryProjectionBatch batch);
    List<MemoryGraphHit> expand(Long userId, List<Long> seedFactIds, int maxHops);
}
