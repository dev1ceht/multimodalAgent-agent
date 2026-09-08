package com.multimodalAgent.agent.service.memory;

import com.multimodalAgent.agent.domain.LongTermMemoryTask;
import com.multimodalAgent.agent.repository.LongTermMemoryTaskRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LongTermMemoryService implements LongTermMemoryWriter {

    private final LongTermMemoryTaskRepository taskRepository;

    public LongTermMemoryService(LongTermMemoryTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Override
    @Transactional
    public MemoryTaskReceipt enqueue(MemoryInput input) {
        return taskRepository.findBySourceMessageId(input.messageId())
                .map(task -> new MemoryTaskReceipt(task.getId()))
                .orElseGet(() -> new MemoryTaskReceipt(taskRepository.save(newTask(input)).getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public MemoryTaskStatus status(String taskId) {
        return taskRepository.findById(taskId)
                .map(LongTermMemoryTask::getStatus)
                .orElseThrow(() -> new IllegalArgumentException("Long-term memory task not found."));
    }

    private LongTermMemoryTask newTask(MemoryInput input) {
        LongTermMemoryTask task = new LongTermMemoryTask();
        task.setId(UUID.randomUUID().toString());
        task.setSourceMessageId(input.messageId());
        task.setUserId(input.userId());
        task.setSessionId(input.sessionId());
        task.setSessionPublicId(input.sessionPublicId());
        task.setRole(input.role());
        task.setContent(input.content());
        task.setOccurredAt(input.occurredAt());
        return task;
    }
}
