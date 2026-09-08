package com.multimodalAgent.agent.service.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.LongTermMemoryTask;
import com.multimodalAgent.agent.domain.MessageRole;
import com.multimodalAgent.agent.repository.LongTermMemoryTaskRepository;
import com.multimodalAgent.agent.repository.MemoryFactRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@Import({LongTermMemoryTaskExecutor.class, LongTermMemoryTaskExecutorTests.Config.class})
@TestPropertySource(properties = "multimodal-agent.memory.enabled=true")
class LongTermMemoryTaskExecutorTests {

    @Autowired LongTermMemoryTaskExecutor executor;
    @Autowired LongTermMemoryTaskRepository tasks;
    @MockBean MemoryFactRepository facts;
    @MockBean MemoryCompiler compiler;
    @MockBean MemoryPersistenceService persistence;
    @MockBean MemoryVectorStore vectors;
    @MockBean MemoryGraphStore graph;

    @Test
    void expiredProcessingLeaseIsReclaimedAndCompleted() {
        LongTermMemoryTask task = task(MemoryTaskStatus.PROCESSING);
        task.setAttempts(1);
        task.setLeaseToken("abandoned-worker");
        task.setLeaseUntil(Instant.now().minusSeconds(1));
        tasks.saveAndFlush(task);
        arrangeSuccessfulProjection(task);

        executor.pollDueTasks();

        LongTermMemoryTask completed = tasks.findById(task.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(MemoryTaskStatus.SUCCEEDED);
        assertThat(completed.getAttempts()).isEqualTo(2);
        assertThat(completed.getLeaseToken()).isNull();
        assertThat(completed.getLeaseUntil()).isNull();
        verify(vectors).upsert(any(), any());
        verify(graph).upsert(any(), any());
    }

    @Test
    void liveProcessingLeaseIsNotExecutedAgain() {
        LongTermMemoryTask task = task(MemoryTaskStatus.PROCESSING);
        task.setLeaseToken("active-worker");
        task.setLeaseUntil(Instant.now().plusSeconds(60));
        tasks.saveAndFlush(task);

        executor.pollDueTasks();

        assertThat(tasks.findById(task.getId()).orElseThrow().getLeaseToken())
                .isEqualTo("active-worker");
        verify(compiler, never()).compile(any());
        verify(vectors, never()).upsert(any(), any());
        verify(graph, never()).upsert(any(), any());
    }

    @Test
    void workerThatLosesLeaseAfterCompilationCannotPersistOrProject() {
        LongTermMemoryTask task = task(MemoryTaskStatus.PENDING);
        tasks.saveAndFlush(task);
        when(facts.findByUserIdOrderByOccurredAtDesc(any(), any())).thenReturn(List.of());
        when(compiler.compile(any())).thenAnswer(ignored -> {
            LongTermMemoryTask takenOver = tasks.findById(task.getId()).orElseThrow();
            takenOver.setLeaseToken("replacement-worker");
            takenOver.setLeaseUntil(Instant.now().plusSeconds(60));
            tasks.saveAndFlush(takenOver);
            return new CompiledMemory(List.of(), List.of(), List.of(), List.of());
        });

        executor.pollDueTasks();

        assertThat(tasks.findById(task.getId()).orElseThrow().getLeaseToken())
                .isEqualTo("replacement-worker");
        verify(persistence, never()).persist(any(), any());
        verify(vectors, never()).upsert(any(), any());
        verify(graph, never()).upsert(any(), any());
    }

    private void arrangeSuccessfulProjection(LongTermMemoryTask task) {
        CompiledMemory compiled = new CompiledMemory(List.of(), List.of(), List.of(), List.of());
        MemoryProjectionBatch batch = new MemoryProjectionBatch(
                task.getUserId(), List.of(), List.of(), List.of(), List.of());
        when(facts.findByUserIdOrderByOccurredAtDesc(any(), any())).thenReturn(List.of());
        when(compiler.compile(any())).thenReturn(compiled);
        when(persistence.persist(any(), any())).thenReturn(batch);
    }

    private LongTermMemoryTask task(MemoryTaskStatus status) {
        LongTermMemoryTask task = new LongTermMemoryTask();
        task.setId(UUID.randomUUID().toString());
        task.setSourceMessageId(101L);
        task.setUserId(11L);
        task.setSessionId(22L);
        task.setSessionPublicId("session-public-id");
        task.setRole(MessageRole.USER);
        task.setContent("我把答辩改到了周五。");
        task.setOccurredAt(Instant.now());
        task.setStatus(status);
        return task;
    }

    @TestConfiguration
    static class Config {
        @Bean multimodalAgentProperties properties() {
            multimodalAgentProperties properties = new multimodalAgentProperties();
            properties.getMemory().setEnabled(true);
            return properties;
        }
    }
}
