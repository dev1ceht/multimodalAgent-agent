package com.multimodalAgent.agent.dto;

import com.multimodalAgent.agent.domain.KnowledgeUploadStatus;

/** Public upload state; object storage coordinates are intentionally omitted. */
public record KnowledgeUploadResponse(
        String uploadId,
        String source,
        KnowledgeUploadStatus status,
        String statusUrl,
        Long documentId,
        String knowledgeVersionKey,
        String publicationStatus,
        int attempts,
        boolean retryable,
        String lastErrorCode,
        String lastErrorMessage,
        long sizeBytes
) {
}
