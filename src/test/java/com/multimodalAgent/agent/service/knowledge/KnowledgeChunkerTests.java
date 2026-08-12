package com.multimodalAgent.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeChunkerTests {

    private final KnowledgeChunker chunker = new KnowledgeChunker();

    @Test
    void plansParentSectionsAndSearchableChildrenWithHeadingContext() {
        String content = """
                # 学业压力

                ## 可以尝试的小步骤

                先把任务改写成一个具体问题，再拆成今天能够完成的小步骤。选择一个优先项，并说明完成标准。

                如果任务仍然很多，可以联系教师、助教或同伴学习小组一起梳理。

                ## 何时升级求助

                如果压力持续影响出勤、睡眠或基本生活，应考虑联系专业支持。
                """;

        ChunkPlan plan = chunker.plan(
                new KnowledgeDocumentInput("academic.md", content),
                new ChunkingPolicy("HIERARCHICAL_V1", 900, 70, 120, 180, 20));

        assertThat(plan.parents()).hasSize(3);
        ParentChunk steps = plan.parents().stream()
                .filter(parent -> parent.sectionPath().endsWith("可以尝试的小步骤"))
                .findFirst()
                .orElseThrow();
        assertThat(steps.content()).contains("先把任务", "联系教师");
        assertThat(steps.children()).isNotEmpty();
        assertThat(steps.children())
                .allSatisfy(child -> {
                    assertThat(child.parentKey()).isEqualTo(steps.parentKey());
                    assertThat(child.searchText())
                            .startsWith("学业压力 > 可以尝试的小步骤\n");
                    assertThat(child.startOffset()).isGreaterThanOrEqualTo(steps.startOffset());
                    assertThat(child.endOffset()).isLessThanOrEqualTo(steps.endOffset());
                });
    }

    @Test
    void hierarchicalPlanIsDeterministicAndNeverOverlapsAcrossParents() {
        String content = """
                # 睡眠支持

                ## 日常调整

                固定起床时间。减少睡前刺激。记录咖啡、午睡和运动情况。

                ## 医疗评估

                持续影响日常生活或伴随呼吸暂停时，应考虑医疗评估。
                """;
        KnowledgeDocumentInput document = new KnowledgeDocumentInput("sleep.md", content);
        ChunkingPolicy policy = new ChunkingPolicy("HIERARCHICAL_V1", 900, 30, 50, 80, 15);

        ChunkPlan first = chunker.plan(document, policy);
        ChunkPlan second = chunker.plan(document, policy);

        assertThat(first).isEqualTo(second);
        assertThat(first.children()).allSatisfy(child -> {
            ParentChunk parent = first.parent(child.parentKey()).orElseThrow();
            assertThat(child.startOffset()).isBetween(parent.startOffset(), parent.endOffset());
            assertThat(child.endOffset()).isBetween(parent.startOffset(), parent.endOffset());
        });
    }

    @Test
    void preservesPageRangesWhenExtractedPdfPagesUseFormFeedMarkers() {
        String content = "# 第一页\n\n第一页内容。\n\f\n# 第二页\n\n第二页内容。";

        ChunkPlan plan = chunker.plan(
                new KnowledgeDocumentInput("guide.pdf", content),
                ChunkingPolicy.hierarchicalDefaults());

        assertThat(plan.parents()).hasSize(2);
        assertThat(plan.parents().get(0).pageStart()).isEqualTo(1);
        assertThat(plan.parents().get(0).pageEnd()).isEqualTo(1);
        assertThat(plan.parents().get(1).pageStart()).isEqualTo(2);
        assertThat(plan.parents().get(1).children())
                .allSatisfy(child -> assertThat(child.pageStart()).isEqualTo(2));
    }

    @Test
    void keepsReferenceSectionAsParentMetadataButDoesNotIndexItAsAdvice() {
        ChunkPlan plan = chunker.plan(
                new KnowledgeDocumentInput(
                        "guide.md",
                        "# 指南\n\n## 建议\n\n保持规律作息。\n\n## 来源\n\n- 权威机构：https://example.org"),
                ChunkingPolicy.hierarchicalDefaults());

        ParentChunk sources = plan.parents().stream()
                .filter(parent -> parent.sectionPath().endsWith("来源"))
                .findFirst()
                .orElseThrow();
        assertThat(sources.content()).contains("权威机构");
        assertThat(sources.children()).isEmpty();
        assertThat(plan.children()).noneMatch(child -> child.content().contains("example.org"));
    }

    @Test
    void keepsLowerLevelHeadingsInsideTheirH2Parent() {
        ChunkPlan plan = chunker.plan(
                new KnowledgeDocumentInput(
                        "guide.md",
                        "# 指南\n\n## 调整方法\n\n### 第一步\n\n记录现状。\n\n### 第二步\n\n逐步调整。"),
                ChunkingPolicy.hierarchicalDefaults());

        assertThat(plan.parents()).hasSize(2);
        ParentChunk parent = plan.parents().get(1);
        assertThat(parent.sectionPath()).isEqualTo("指南 > 调整方法");
        assertThat(parent.content()).contains("### 第一步", "### 第二步");
    }

    @Test
    void offsetsReferToTheOriginalCrLfDocument() {
        String content = "\r\n# 指南\r\n\r\n## 建议\r\n\r\n保持规律作息。\r\n";

        ChunkPlan plan = chunker.plan(
                new KnowledgeDocumentInput("guide.md", content),
                ChunkingPolicy.hierarchicalDefaults());

        ParentChunk advice = plan.parents().get(1);
        assertThat(content.substring(advice.startOffset(), advice.endOffset()))
                .isEqualTo(advice.content());
    }

    @Test
    void neverCreatesAParentThatCrossesAnExtractedPdfPage() {
        ChunkPlan plan = chunker.plan(
                new KnowledgeDocumentInput(
                        "guide.pdf",
                        "# 指南\n\n## 建议\n\n第一页建议。\n\f\n第二页继续说明。"),
                ChunkingPolicy.hierarchicalDefaults());

        assertThat(plan.parents()).allSatisfy(parent ->
                assertThat(parent.pageStart()).isEqualTo(parent.pageEnd()));
    }

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
