package com.multimodalAgent.agent.domain;

/**
 * 投递任务的持久化状态。
 */
public enum DeliveryTaskStatus {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED
}
