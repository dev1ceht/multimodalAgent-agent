package com.multimodalAgent.agent.service.memory;

import java.time.Instant;

public record CompiledFact(String content, Instant occurredAt) {
}
