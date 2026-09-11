package com.multimodalAgent.agent.dto;

import java.util.List;

public record KnowledgeUploadPage(
        List<KnowledgeUploadResponse> uploads,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
