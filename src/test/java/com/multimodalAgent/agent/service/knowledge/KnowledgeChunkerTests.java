package com.multimodalAgent.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeChunkerTests {

    private final KnowledgeChunker chunker = new KnowledgeChunker();

    @Test
    void repeatsHeadingWhenASectionNeedsMultipleChunks() {
        String content = """
                # 睡眠支持

                ## 适用场景

                学生连续多日难以入睡，并且白天注意力下降。可以先记录作息和影响因素。

                建议保持固定起床时间，减少睡前刺激，并观察两周内的变化。

                白天可以记录咖啡因、午睡、运动和屏幕使用情况，避免因为一晚睡不好就大幅提前上床。

                如果躺了较长时间仍然清醒，可以暂时离开床铺做低刺激活动，困倦后再回到床上。

                如果症状持续或明显影响学习生活，应联系专业人员评估。
                """;

        List<String> chunks = chunker.chunk(content, 120, 20);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.stream().filter(value -> value.startsWith("## 适用场景")).count())
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void keepsAListTogetherWhenItFitsInOneChunk() {
        String content = """
                ## 即时安全步骤

                - 离开危险物品和危险地点。
                - 联系身边可信任的人。
                - 必要时联系当地紧急服务。
                """;

        List<String> chunks = chunker.chunk(content, 300, 40);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains(
                "离开危险物品",
                "联系身边可信任的人",
                "联系当地紧急服务");
    }
}
