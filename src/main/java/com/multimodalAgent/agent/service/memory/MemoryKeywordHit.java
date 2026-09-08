package com.multimodalAgent.agent.service.memory;

/** A lexical candidate returned by the long-term memory keyword index. */
public record MemoryKeywordHit(Long factId, double score) {
}
