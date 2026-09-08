package com.multimodalAgent.agent.service.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.multimodalAgent.agent.domain.MessageRole;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import(LongTermMemoryService.class)
class LongTermMemoryServiceTests {

    @Autowired
    private LongTermMemoryService memoryService;

    @Test
    void onePersistedMessageCreatesOneQueryableMemoryTask() {
        MemoryInput input = new MemoryInput(
                11L,
                22L,
                "session-public-id",
                101L,
                MessageRole.USER,
                "我把答辩改到了周五，因为周三还要复查数据。",
                Instant.parse("2026-09-07T08:00:00Z"));

        MemoryTaskReceipt first = memoryService.enqueue(input);
        MemoryTaskReceipt duplicate = memoryService.enqueue(input);

        assertThat(duplicate.taskId()).isEqualTo(first.taskId());
        assertThat(memoryService.status(first.taskId()))
                .isEqualTo(MemoryTaskStatus.PENDING);
    }
}
