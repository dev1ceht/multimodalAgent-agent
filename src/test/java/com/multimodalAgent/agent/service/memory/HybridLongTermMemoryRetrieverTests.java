package com.multimodalAgent.agent.service.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.MemoryFact;
import com.multimodalAgent.agent.domain.MemoryFactTopic;
import com.multimodalAgent.agent.domain.MemoryTopic;
import com.multimodalAgent.agent.repository.MemoryFactRepository;
import com.multimodalAgent.agent.repository.MemoryFactTopicRepository;
import com.multimodalAgent.agent.repository.MemoryTopicRepository;
import com.multimodalAgent.agent.service.knowledge.EmbeddingClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@Import({HybridLongTermMemoryRetriever.class, HybridLongTermMemoryRetrieverTests.Config.class})
@TestPropertySource(properties = "multimodal-agent.memory.enabled=true")
class HybridLongTermMemoryRetrieverTests {
    @Autowired HybridLongTermMemoryRetriever retriever;
    @Autowired MemoryFactRepository facts;
    @Autowired MemoryTopicRepository topics;
    @Autowired MemoryFactTopicRepository memberships;
    @MockBean MemoryVectorStore vectors;
    @MockBean MemoryKeywordStore keywords;
    @MockBean MemoryGraphStore graph;
    @MockBean EmbeddingClient embeddings;

    @Test
    void fusesVectorTopicGraphAndTemporalContextWithinTheSameUser() {
        MemoryFact before = fact("周三复查数据", 11L, 1L, "2026-09-09T09:00:00Z");
        MemoryFact seed = fact("周五参加答辩", 11L, 2L, "2026-09-11T09:00:00Z");
        MemoryFact graphFact = fact("复查数据", 12L, 3L, "2026-09-07T09:00:00Z");
        MemoryFact keywordFact = fact("答辩改期需要通知导师", 13L, 4L, "2026-09-08T09:00:00Z");
        MemoryTopic topic = new MemoryTopic();
        topic.setUserId(7L); topic.setTopicKey("defense"); topic.setTitle("毕业答辩");
        topic.setSummary("答辩安排与准备事项"); topic.setProjectionRevision(2); topic = topics.save(topic);
        MemoryFactTopic membership = new MemoryFactTopic();
        membership.setFactId(before.getId()); membership.setTopicId(topic.getId()); memberships.save(membership);
        when(embeddings.embed("答辩为何改期")).thenReturn(List.of(0.1, 0.2));
        when(vectors.searchFacts(7L, List.of(0.1, 0.2), 24)).thenReturn(List.of(
                new MemoryVectorHit(seed.getId(), seed.getContent(), 0.9, 11L, seed.getOccurredAt())));
        when(keywords.searchFacts(7L, "答辩为何改期", 24)).thenReturn(List.of(
                new MemoryKeywordHit(keywordFact.getId(), 4.2)));
        when(vectors.searchTopics(7L, List.of(0.1, 0.2), 32)).thenReturn(List.of(
                new MemoryVectorHit(topic.getId(), "过期主题摘要", 0.99, null, null, 1L),
                new MemoryVectorHit(topic.getId(), topic.getTitle(), 0.8, null, null,
                        topic.getProjectionRevision())));
        when(graph.expand(eq(7L), anyList(), eq(3))).thenReturn(List.of(
                new MemoryGraphHit(graphFact.getId(), graphFact.getContent(), MemoryRelationType.CAUSES, 1,
                        "复查数据 -[CAUSES]-> 周五参加答辩")));

        LongTermMemoryRecall recall = retriever.recall(new LongTermMemoryQuery(7L, 11L, "答辩为何改期"));

        assertThat(recall.status()).isEqualTo(LongTermMemoryRecall.Status.READY);
        assertThat(recall.items()).extracting(LongTermMemoryRecall.Item::content)
                .contains("周五参加答辩", "周三复查数据", "复查数据", "答辩改期需要通知导师");
        assertThat(recall.context()).contains("主题：毕业答辩",
                "事实[2026-09-11T09:00:00Z]：周五参加答辩",
                "关系链：复查数据 -[CAUSES]-> 周五参加答辩");
        assertThat(recall.items()).extracting(LongTermMemoryRecall.Item::source)
                .anyMatch(source -> source.contains("temporal"))
                .anyMatch(source -> source.contains("bm25"))
                .anyMatch(source -> source.contains("graph:CAUSES"));
        assertThat(recall.reason()).isEqualTo("vector+bm25+topic+graph+temporal");
    }

    @Test
    void keepsAnExactBm25OnlyHitWhenDenseCandidatePoolIsFull() {
        List<MemoryVectorHit> denseHits = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            MemoryFact dense = fact("语义候选-" + i, 100L + i, 1000L + i,
                    "2026-09-01T09:" + String.format("%02d", i) + ":00Z");
            denseHits.add(new MemoryVectorHit(dense.getId(), dense.getContent(),
                    0.99 - i * 0.01, dense.getSessionId(), dense.getOccurredAt()));
        }
        MemoryFact exact = fact("项目代号是蓝鲸-739", 999L, 2000L, "2026-09-02T09:00:00Z");
        when(embeddings.embed("蓝鲸-739")).thenReturn(List.of(0.1, 0.2));
        when(vectors.searchFacts(7L, List.of(0.1, 0.2), 24)).thenReturn(denseHits);
        when(keywords.searchFacts(7L, "蓝鲸-739", 24)).thenReturn(
                List.of(new MemoryKeywordHit(exact.getId(), 9.0)));

        LongTermMemoryRecall recall = retriever.recall(
                new LongTermMemoryQuery(7L, 999L, "蓝鲸-739"));

        assertThat(recall.status()).isEqualTo(LongTermMemoryRecall.Status.READY);
        assertThat(recall.items()).extracting(LongTermMemoryRecall.Item::factId)
                .contains(exact.getId());
        assertThat(recall.items().stream()
                .filter(item -> item.factId().equals(exact.getId()))
                .findFirst().orElseThrow().source()).contains("bm25");
    }

    private MemoryFact fact(String content, Long sessionId, Long messageId, String time) {
        MemoryFact fact = new MemoryFact();
        fact.setUserId(7L); fact.setSessionId(sessionId); fact.setSourceMessageId(messageId);
        fact.setFactOrdinal(0); fact.setContent(content); fact.setOccurredAt(Instant.parse(time));
        return facts.save(fact);
    }

    @TestConfiguration
    static class Config {
        @Bean multimodalAgentProperties properties() {
            multimodalAgentProperties properties = new multimodalAgentProperties();
            properties.getMemory().setEnabled(true);
            properties.getMemory().setBm25Enabled(true);
            properties.getMemory().setBm25CandidateMultiplier(3);
            properties.getMemory().setBm25Weight(0.3);
            properties.getMemory().setBm25FusionMethod("rrf");
            return properties;
        }
    }
}
