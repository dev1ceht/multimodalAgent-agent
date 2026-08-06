package com.multimodalAgent.agent.service.knowledge.retrieval;

/**
 * 检索模块的最小输入。上层不需要知道向量、Chroma collection 或数据库查询细节。
 */
public record RetrievalQuery(String text, int topK) {

    private static final int MAX_TOP_K = 20;

    public RetrievalQuery {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Retrieval query must not be blank.");
        }
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new IllegalArgumentException("Retrieval topK must be between 1 and " + MAX_TOP_K + ".");
        }
        text = text.trim();
    }
}
