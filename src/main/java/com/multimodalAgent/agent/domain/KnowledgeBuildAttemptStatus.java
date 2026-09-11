package com.multimodalAgent.agent.domain;

/** State of an isolated external index build attempt. */
public enum KnowledgeBuildAttemptStatus {
    BUILDING,
    SUCCEEDED,
    FAILED,
    ABANDONED
}
