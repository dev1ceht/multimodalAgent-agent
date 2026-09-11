package com.multimodalAgent.agent.domain;

/** Versioned Kafka event types used by the knowledge pipeline. */
public enum KnowledgeEventType {
    KNOWLEDGE_PARSE_REQUESTED,
    KNOWLEDGE_INDEX_REQUESTED,
    KNOWLEDGE_DEAD_LETTER
}
