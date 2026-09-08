package com.multimodalAgent.agent.service.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.multimodalAgent.agent.domain.MemoryFact;
import com.multimodalAgent.agent.repository.MemoryFactRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import(Bm25MemoryKeywordStore.class)
class Bm25MemoryKeywordStoreTests {
    @Autowired MemoryFactRepository facts;
    @Autowired MemoryKeywordStore keywords;

    @Test
    void ranksFactsByChineseAndLatinKeywordOverlap() {
        MemoryFact target = save(17L, "周五参加毕业答辩，准备 defense slides");
        save(17L, "周三复查实验数据");
        save(17L, "整理课程作业");

        List<MemoryKeywordHit> hits = keywords.searchFacts(17L, "答辩 defense", 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).factId()).isEqualTo(target.getId());
        assertThat(hits.get(0).score()).isPositive();
    }

    @Test
    void isolatesIndexesByUser() {
        MemoryFact otherUser = save(28L, "答辩安排只属于另一位用户");
        save(27L, "周三复查实验数据");

        assertThat(keywords.searchFacts(27L, "答辩", 5))
                .noneMatch(hit -> hit.factId().equals(otherUser.getId()));
    }

    @Test
    void incrementallyAddsProjectedFactsToAnAlreadyLoadedIndex() {
        MemoryFact first = save(37L, "已有答辩安排");
        assertThat(keywords.searchFacts(37L, "部署回滚", 5)).isEmpty();
        MemoryFact projected = save(37L, "上线部署后可以回滚");

        keywords.upsert(new MemoryProjectionBatch(37L,
                List.of(new MemoryProjectionBatch.Fact(projected.getId(), projected.getSessionId(),
                        projected.getContent(), projected.getOccurredAt())),
                List.of(), List.of(), List.of()));

        assertThat(keywords.searchFacts(37L, "部署回滚", 5))
                .extracting(MemoryKeywordHit::factId)
                .contains(projected.getId())
                .doesNotContain(first.getId());
    }

    private MemoryFact save(Long userId, String content) {
        MemoryFact fact = new MemoryFact();
        fact.setUserId(userId);
        fact.setSessionId(1L);
        fact.setSourceMessageId(System.nanoTime());
        fact.setFactOrdinal(0);
        fact.setContent(content);
        fact.setOccurredAt(Instant.now());
        return facts.saveAndFlush(fact);
    }
}
