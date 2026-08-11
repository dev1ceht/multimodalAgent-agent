package com.multimodalAgent.agent.service.knowledge;

import com.multimodalAgent.agent.domain.KnowledgeIndexTaskStatus;
import com.multimodalAgent.agent.domain.KnowledgeVersionStatus;
import java.time.Instant;

/** Publication and indexing state shown in the knowledge administration history. */
public record KnowledgeVersionSummary(
        String versionKey,
        KnowledgeVersionStatus status,
        int sourceCount,
        int chunkCount,
        KnowledgeIndexTaskStatus taskStatus,
        int taskAttempts,
        String lastError,
        Instant createdAt,
        Instant activatedAt,
        boolean latest,
        boolean active,
        boolean retryable
) {
}
