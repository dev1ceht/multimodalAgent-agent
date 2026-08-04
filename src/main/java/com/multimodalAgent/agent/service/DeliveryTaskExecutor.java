package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.domain.DeliveryTask;

/**
 * External effect seam for one persisted delivery task.
 *
 * <p>The caller owns leasing and durable state. Implementations only dispatch
 * the task to the appropriate external tool and propagate its failure.</p>
 */
public interface DeliveryTaskExecutor {

    void execute(DeliveryTask task);
}
