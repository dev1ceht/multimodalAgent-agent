package com.multimodalAgent.agent.service.memory;

import java.util.List;

/** Keyword index used to complement dense Fact retrieval. */
public interface MemoryKeywordStore {
    void upsert(MemoryProjectionBatch batch);

    default void upsert(MemoryProjectionBatch batch, Runnable leaseGuard) {
        leaseGuard.run();
        upsert(batch);
    }

    List<MemoryKeywordHit> searchFacts(Long userId, String query, int limit);
}
