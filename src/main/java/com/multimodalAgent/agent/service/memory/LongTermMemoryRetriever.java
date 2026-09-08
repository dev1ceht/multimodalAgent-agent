package com.multimodalAgent.agent.service.memory;

public interface LongTermMemoryRetriever {
    LongTermMemoryRecall recall(LongTermMemoryQuery query);
}
