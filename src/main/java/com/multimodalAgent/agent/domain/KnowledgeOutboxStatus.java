package com.multimodalAgent.agent.domain;

/** Delivery state for a database outbox event. */
public enum KnowledgeOutboxStatus {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    PUBLISHED
}
