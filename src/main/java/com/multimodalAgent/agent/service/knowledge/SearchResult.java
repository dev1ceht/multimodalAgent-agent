package com.multimodalAgent.agent.service.knowledge;

/**
 * RAG 检索结果。
 *
 * @param chunkId 数据库切块 id，外部检索结果没有 id 时可以为空
 * @param source 知识来源文件或来源名
 * @param content 命中的文本内容
 * @param score 检索相关性分数，越高越相关
 * @param provenance 命中知识版本、向量标识和来源切块位置
 */
public record SearchResult(
        Long chunkId,
        String source,
        String content,
        double score,
        EvidenceProvenance provenance
) {

    public SearchResult(Long chunkId, String source, String content, double score) {
        this(chunkId, source, content, score, EvidenceProvenance.unknown());
    }

    public SearchResult {
        provenance = provenance == null ? EvidenceProvenance.unknown() : provenance;
    }

    public SearchResult withScore(double newScore) {
        return new SearchResult(chunkId, source, content, newScore, provenance);
    }

    public SearchResult withContent(String newContent) {
        return new SearchResult(chunkId, source, newContent, score, provenance);
    }

    public SearchResult withProvenance(EvidenceProvenance newProvenance) {
        return new SearchResult(chunkId, source, content, score, newProvenance);
    }
}
