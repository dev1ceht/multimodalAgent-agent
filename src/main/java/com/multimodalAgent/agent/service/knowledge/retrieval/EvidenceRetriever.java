package com.multimodalAgent.agent.service.knowledge.retrieval;

/**
 * Agentic RAG 使用的外部检索 interface。
 *
 * <p>实现必须返回可追溯证据，并明确区分 READY、EMPTY 和 FAILED。调用方不应依赖具体
 * 向量库、Embedding 客户端或本地 baseline 的实现细节。</p>
 */
public interface EvidenceRetriever {

    RetrievalResult retrieve(RetrievalQuery query);
}
