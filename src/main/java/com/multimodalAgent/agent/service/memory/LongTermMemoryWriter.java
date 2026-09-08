package com.multimodalAgent.agent.service.memory;

/** Public write seam for durable asynchronous conversation-memory compilation. */
public interface LongTermMemoryWriter {

    MemoryTaskReceipt enqueue(MemoryInput input);

    MemoryTaskStatus status(String taskId);
}
