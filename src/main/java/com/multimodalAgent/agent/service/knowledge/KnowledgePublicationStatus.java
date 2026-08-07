package com.multimodalAgent.agent.service.knowledge;

import com.multimodalAgent.agent.domain.KnowledgeIndexTaskStatus;
import com.multimodalAgent.agent.domain.KnowledgeVersionStatus;
import java.time.Instant;

/** 管理员查看知识版本发布和索引任务进度的只读结果。 */
public record KnowledgePublicationStatus(
        String activeVersionKey,
        KnowledgeVersionStatus activeVersionStatus,
        String latestVersionKey,
        KnowledgeVersionStatus latestVersionStatus,
        KnowledgeIndexTaskStatus latestTaskStatus,
        int latestTaskAttempts,
        String latestTaskError,
        int latestSourceCount,
        int latestChunkCount,
        Instant latestCreatedAt,
        Instant activeActivatedAt,
        boolean retrievalReady
) {
}
