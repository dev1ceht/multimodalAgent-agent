package com.multimodalAgent.agent.service.knowledge;

/** 一次知识发布中提交的一份来源材料。 */
public record KnowledgeDocumentInput(String source, String content) {

    public KnowledgeDocumentInput {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Knowledge document source must not be blank.");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Knowledge document content must not be blank.");
        }
        source = source.trim();
    }
}
