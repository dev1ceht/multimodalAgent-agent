package com.multimodalAgent.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Optimistic-locking update request for an existing canonical knowledge document. */
public record KnowledgeDocumentUpdateRequest(
        @NotBlank @Size(max = 180) String source,
        @NotBlank String content,
        @PositiveOrZero long version
) {
}
