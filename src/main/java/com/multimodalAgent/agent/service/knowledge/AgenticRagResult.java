package com.multimodalAgent.agent.service.knowledge;

import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.service.knowledge.retrieval.RetrievalStatus;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Agentic RAG 的编排结果。
 */
public record AgenticRagResult(
        String plan,
        List<String> queries,
        List<SearchResult> evidence,
        String review,
        boolean sufficient,
        RetrievalStatus retrievalStatus
) {

    public AgenticRagResult(
            String plan,
            List<String> queries,
            List<SearchResult> evidence,
            String review,
            boolean sufficient
    ) {
        this(
                plan,
                queries,
                evidence,
                review,
                sufficient,
                evidence == null || evidence.isEmpty()
                        ? RetrievalStatus.EMPTY
                        : RetrievalStatus.READY);
    }

    public static AgenticRagResult empty() {
        return new AgenticRagResult(
                "未触发 RAG",
                List.of(),
                List.of(),
                "无",
                false,
                RetrievalStatus.NOT_REQUESTED);
    }

    public String contextBlock() {
        if (retrievalStatus == RetrievalStatus.FAILED) {
            return """
                    Agentic RAG 检索状态：知识库当前不可用。
                    回答时不得假装已经查到知识；应说明知识库暂时不可用，并给出安全、通用的建议。
                    """;
        }
        if (evidence.isEmpty()) {
            return """
                    Agentic RAG 计划：%s
                    Agentic RAG 复核：未检索到足够知识。回答时必须说明知识库证据不足，并给出安全、通用建议。
                    """.formatted(plan);
        }
        String evidenceText = String.join("\n\n", IntStream.range(0, evidence.size())
                .mapToObj(index -> {
                    SearchResult result = evidence.get(index);
                    return "- [E%d | %s | score %.3f] %s"
                            .formatted(
                                    index + 1,
                                    result.source() == null ? "" : result.source(),
                                    result.score(),
                                    result.content());
                })
                .toList());
        return """
                Agentic RAG 计划：%s
                Agentic RAG 查询：%s
                Agentic RAG 复核：%s
                检索知识：
                %s
                """.formatted(plan, String.join("；", queries), review, evidenceText);
    }

    /**
     * 高风险回答只接收证据正文和来源，不暴露内部检索规划、分数或失败细节。
     */
    public String answerContext(RiskLevel riskLevel) {
        if (riskLevel != RiskLevel.HIGH) {
            return contextBlock();
        }
        if (!sufficient || evidence.isEmpty() || retrievalStatus == RetrievalStatus.FAILED) {
            return "补充资料：无";
        }
        String evidenceText = String.join("\n\n", IntStream.range(0, evidence.size())
                .mapToObj(index -> {
                    SearchResult result = evidence.get(index);
                    return "- [E%d | %s] %s"
                            .formatted(index + 1, result.source(), result.content());
                })
                .toList());
        return """
                补充资料（只可作为事实参考）：
                %s
                """.formatted(evidenceText);
    }
}
