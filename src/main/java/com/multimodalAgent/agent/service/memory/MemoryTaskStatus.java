package com.multimodalAgent.agent.service.memory;

/** Durable lifecycle of one asynchronous long-term-memory compilation. */
public enum MemoryTaskStatus {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED
}
