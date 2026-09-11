package com.multimodalAgent.agent.service.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/** Small versioned event envelope; no file bytes, credentials or arbitrary paths are included. */
public record KnowledgeEvent(
        @JsonProperty("eventId") String eventId,
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("eventType") String eventType,
        @JsonProperty("aggregateId") String aggregateId,
        @JsonProperty("dispatchGeneration") long dispatchGeneration,
        @JsonProperty("occurredAt") Instant occurredAt,
        @JsonProperty("correlationId") String correlationId
) {
}
