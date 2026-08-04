package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.domain.DeliveryTask;

/**
 * Records the lifecycle of a concrete alert notification attempt.
 *
 * <p>The caller must invoke the methods in order for the same alert task:
 * start once, then succeed, fail, or mark the attempt unknown once.</p>
 */
public interface NotificationAttemptRecorder {

    void recordStarted(DeliveryTask task);

    void recordSucceeded(DeliveryTask task);

    void recordFailed(DeliveryTask task, String errorMessage);

    void recordUnknown(DeliveryTask task, String reason);
}
