package com.multimodalAgent.agent.domain;

/** Durable lifecycle of an administrator knowledge file upload. */
public enum KnowledgeUploadStatus {
    STORING,
    STORED,
    PARSING,
    PARSED,
    STORAGE_FAILED,
    RETRY_WAIT,
    FAILED,
    CONFLICT
}
