package com.multimodalAgent.agent.service.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.domain.LongTermMemoryTask;
import com.multimodalAgent.agent.domain.MemoryFact;
import com.multimodalAgent.agent.domain.MessageRole;
import com.multimodalAgent.agent.repository.LongTermMemoryTaskRepository;
import com.multimodalAgent.agent.repository.MemoryFactRepository;
import com.multimodalAgent.agent.repository.MemoryRelationRepository;
import com.multimodalAgent.agent.repository.MemoryTopicRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import({MemoryPersistenceService.class, MemoryPersistenceServiceTests.Config.class})
class MemoryPersistenceServiceTests {
    @Autowired MemoryPersistenceService persistence;
    @Autowired LongTermMemoryTaskRepository tasks;
    @Autowired MemoryFactRepository facts;
    @Autowired MemoryRelationRepository relations;
    @Autowired MemoryTopicRepository topics;

    @Test
    void retryReplaysFrozenCompilationWhenCompilerOrderChanges() {
        LongTermMemoryTask task = task(101L);
        CompiledMemory first = compiled("准备材料", "参加答辩", "new:0", "new:1");
        persistence.persist(lease(task), first);

        CompiledMemory changedRetry = compiled("参加答辩", "准备材料", "new:0", "new:1");
        persistence.persist(lease(task), changedRetry);

        var byId = facts.findAll().stream().collect(Collectors.toMap(MemoryFact::getId, Function.identity()));
        assertThat(relations.findAll()).singleElement().satisfies(relation -> {
            assertThat(byId.get(relation.getSourceFactId()).getContent()).isEqualTo("准备材料");
            assertThat(byId.get(relation.getTargetFactId()).getContent()).isEqualTo("参加答辩");
            assertThat(relation.getType()).isEqualTo(MemoryRelationType.CAUSES);
        });
        assertThat(tasks.findById(task.getId()).orElseThrow().getCompilationJson())
                .contains("准备材料", "参加答辩");
    }

    @Test
    void topicSummaryAccumulatesFactsAcrossTurns() {
        LongTermMemoryTask firstTask = task(201L);
        persistence.persist(lease(firstTask), compiledWithTopic("项目采用Qdrant", "向量数据库"));
        LongTermMemoryTask secondTask = task(202L);
        persistence.persist(lease(secondTask), compiledWithTopic("项目采用Neo4j", "知识图谱"));

        assertThat(topics.findByUserIdAndTopicKey(7L, "architecture")).get().satisfies(topic ->
                assertThat(topic.getSummary()).contains("项目采用Qdrant", "项目采用Neo4j", "向量数据库", "知识图谱"));
    }

    @Test
    void staleWorkerCannotPersistAfterLeaseTakeover() {
        LongTermMemoryTask task = task(301L);

        assertThatThrownBy(() -> persistence.persist(
                new MemoryTaskLease(task.getId(), "stale-token"),
                compiledWithTopic("不应写入", "无效")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lease");
        assertThat(facts.findAll()).isEmpty();
        assertThat(topics.findAll()).isEmpty();
    }

    @Test
    void topicAtBudgetRetainsNewestFactInsteadOfFreezing() {
        LongTermMemoryTask firstTask = task(401L);
        persistence.persist(lease(firstTask), compiledWithTopic("旧".repeat(3990), "旧摘要"));
        LongTermMemoryTask secondTask = task(402L);
        persistence.persist(lease(secondTask), compiledWithTopic("最新关键事实", "新摘要"));

        String summary = topics.findByUserIdAndTopicKey(7L, "architecture").orElseThrow().getSummary();
        assertThat(summary).startsWith("最新关键事实").contains("新摘要").hasSizeLessThanOrEqualTo(4000);
    }

    private CompiledMemory compiled(String first, String second, String source, String target) {
        Instant now = Instant.parse("2026-09-08T08:00:00Z");
        return new CompiledMemory(
                List.of(new CompiledFact(first, now), new CompiledFact(second, now.plusSeconds(60))),
                List.of(),
                List.of(new CompiledRelation(source, target, MemoryRelationType.CAUSES, 0.9)),
                List.of());
    }

    private CompiledMemory compiledWithTopic(String fact, String summary) {
        return new CompiledMemory(
                List.of(new CompiledFact(fact, Instant.parse("2026-09-08T08:00:00Z"))),
                List.of(new CompiledTopic("architecture", "系统架构", summary)),
                List.of(), List.of(new CompiledMembership("new:0", "architecture")));
    }

    private LongTermMemoryTask task(long sourceMessageId) {
        LongTermMemoryTask task = new LongTermMemoryTask();
        task.setId(UUID.randomUUID().toString());
        task.setSourceMessageId(sourceMessageId);
        task.setUserId(7L);
        task.setSessionId(8L);
        task.setSessionPublicId("session-" + sourceMessageId);
        task.setRole(MessageRole.USER);
        task.setContent("source");
        task.setOccurredAt(Instant.parse("2026-09-08T08:00:00Z"));
        task.setStatus(MemoryTaskStatus.PROCESSING);
        task.setLeaseToken("lease-" + sourceMessageId);
        task.setLeaseUntil(Instant.now().plusSeconds(60));
        return tasks.saveAndFlush(task);
    }

    private MemoryTaskLease lease(LongTermMemoryTask task) {
        return new MemoryTaskLease(task.getId(), task.getLeaseToken());
    }

    @TestConfiguration
    static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }
}
