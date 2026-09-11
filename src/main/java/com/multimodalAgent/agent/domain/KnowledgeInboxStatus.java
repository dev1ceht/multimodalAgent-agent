package com.multimodalAgent.agent.domain;

/** Delivery and worker state for an accepted Kafka event. */
public enum KnowledgeInboxStatus {
    QUEUED,
    RUNNING,
    DONE,
    OBSOLETE
}
