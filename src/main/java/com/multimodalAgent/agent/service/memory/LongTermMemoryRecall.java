package com.multimodalAgent.agent.service.memory;

import java.util.List;

public record LongTermMemoryRecall(Status status, List<Item> items, String context, String reason) {
    public enum Status { READY, EMPTY, DEGRADED, DISABLED }
    public record Item(Long factId, String content, double score, String source) {}
    public LongTermMemoryRecall {
        items = List.copyOf(items);
        context = context == null ? "" : context;
        reason = reason == null ? "" : reason;
    }
    public static LongTermMemoryRecall empty(Status status, String reason) {
        return new LongTermMemoryRecall(status, List.of(), "", reason);
    }
}
