package com.multimodalAgent.agent.service.knowledge.retrieval;

import com.multimodalAgent.agent.service.knowledge.SearchResult;
import java.util.List;

/**
 * Ranks a retrieved candidate set before it is handed to the answer workflow.
 *
 * <p>The retriever owns candidate acquisition while this module owns ranking policy. That keeps
 * the production retrieval path replaceable when a cross-encoder or another ranking strategy is
 * introduced later.</p>
 */
public interface EvidenceReranker {

    List<SearchResult> rerank(String query, List<SearchResult> candidates, int limit);
}
