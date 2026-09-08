package com.multimodalAgent.agent.service.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.MemoryFact;
import com.multimodalAgent.agent.domain.MemoryFactTopic;
import com.multimodalAgent.agent.domain.MemoryTopic;
import com.multimodalAgent.agent.repository.MemoryFactRepository;
import com.multimodalAgent.agent.repository.MemoryFactTopicRepository;
import com.multimodalAgent.agent.repository.MemoryTopicRepository;
import com.multimodalAgent.agent.service.knowledge.EmbeddingClient;
import java.time.Instant;
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
    @MockBean MemoryGraphStore graph;
    @MockBean EmbeddingClient embeddings;

    @Test
    void fusesVectorTopicGraphAndTemporalContextWithinTheSameUser() {
        MemoryFact before = fact("周三复查数据", 11L, 1L, "2026-09-09T09:00:00Z");
        MemoryFact seed = fact("周五参加答辩", 11L, 2L, "2026-09-11T09:00:00Z");
        MemoryFact graphFact = fact("复查数据是答辩改期原因", 12L, 3L, "2026-09-07T09:00:00Z");
        MemoryTopic topic = new MemoryTopic();
        topic.setUserId(7L); topic.setTopicKey("defense"); topic.setTitle("毕业答辩");
        topic.setSummary("答辩安排与准备事项"); topic = topics.save(topic);
        MemoryFactTopic membership = new MemoryFactTopic();
        membership.setFactId(before.getId()); membership.setTopicId(topic.getId()); memberships.save(membership);
        when(embeddings.embed("答辩为何改期")).thenReturn(List.of(0.1, 0.2));
        when(vectors.searchFacts(7L, List.of(0.1, 0.2), 8)).thenReturn(List.of(
                new MemoryVectorHit(seed.getId(), seed.getContent(), 0.9, 11L, seed.getOccurredAt())));
        when(vectors.searchTopics(7L, List.of(0.1, 0.2), 4)).thenReturn(List.of(
                new MemoryVectorHit(topic.getId(), topic.getTitle(), 0.8, null, null)));
        when(graph.expand(7L, List.of(seed.getId()), 3)).thenReturn(List.of(
                new MemoryGraphHit(graphFact.getId(), graphFact.getContent(), MemoryRelationType.CAUSES, 1)));

        LongTermMemoryRecall recall = retriever.recall(new LongTermMemoryQuery(7L, 11L, "答辩为何改期"));

        assertThat(recall.status()).isEqualTo(LongTermMemoryRecall.Status.READY);
        assertThat(recall.items()).extracting(LongTermMemoryRecall.Item::content)
                .contains("周五参加答辩", "周三复查数据", "复查数据是答辩改期原因");
        assertThat(recall.context()).contains("主题：毕业答辩", "事实：周五参加答辩");
        assertThat(recall.items()).extracting(LongTermMemoryRecall.Item::source)
                .anyMatch(source -> source.contains("temporal"))
                .anyMatch(source -> source.contains("graph:CAUSES"));
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
            return properties;
        }
    }
}
