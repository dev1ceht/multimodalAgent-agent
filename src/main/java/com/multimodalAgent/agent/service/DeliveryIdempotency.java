package com.multimodalAgent.agent.service;

/**
 * Validates the idempotency key shared by all durable delivery adapters.
 */
public final class DeliveryIdempotency {

    private static final String INVALID_KEY_MESSAGE = "Delivery task idempotency key must not be blank";

    private DeliveryIdempotency() {
    }

    public static String requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalStateException(INVALID_KEY_MESSAGE);
        }
        return idempotencyKey;
    }
}
