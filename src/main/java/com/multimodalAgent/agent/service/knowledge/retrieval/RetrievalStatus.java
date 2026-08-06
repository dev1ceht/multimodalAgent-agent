package com.multimodalAgent.agent.service.knowledge.retrieval;

/**
 * 状态必须区分“没有证据”和“检索后端不可用”，避免调用方把故障当成空结果。
 */
public enum RetrievalStatus {
    NOT_REQUESTED,
    READY,
    EMPTY,
    DEGRADED,
    FAILED
}
