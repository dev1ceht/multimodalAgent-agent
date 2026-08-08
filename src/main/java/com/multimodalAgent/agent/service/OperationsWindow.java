package com.multimodalAgent.agent.service;

import java.time.Instant;

/** Normalized UTC half-open interval used by the operations read model and its audit record. */
public record OperationsWindow(Instant from, Instant to) {
}
