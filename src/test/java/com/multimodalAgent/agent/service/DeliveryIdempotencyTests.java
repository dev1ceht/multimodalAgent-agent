package com.multimodalAgent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DeliveryIdempotencyTests {

    @Test
    void preservesValidKey() {
        assertThat(DeliveryIdempotency.requireKey("alert:42:counselor@example.com"))
                .isEqualTo("alert:42:counselor@example.com");
    }

    @Test
    void rejectsNullAndBlankKeys() {
        assertThatThrownBy(() -> DeliveryIdempotency.requireKey(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Delivery task idempotency key must not be blank");
        assertThatThrownBy(() -> DeliveryIdempotency.requireKey("\t"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Delivery task idempotency key must not be blank");
    }
}
