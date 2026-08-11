package com.multimodalAgent.agent.service.knowledge.retrieval;

import java.util.Locale;

/**
 * RAG 检索后端策略。LOCAL_BASELINE 只能通过显式配置启用。
 */
public enum RetrievalMode {
    ELASTICSEARCH_REQUIRED,
    LOCAL_BASELINE;

    public static RetrievalMode parse(String value) {
        if (value == null || value.isBlank()) {
            return ELASTICSEARCH_REQUIRED;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unsupported RAG retrieval mode: " + value
                            + ". Expected ELASTICSEARCH_REQUIRED or LOCAL_BASELINE.",
                    exception);
        }
    }
}
