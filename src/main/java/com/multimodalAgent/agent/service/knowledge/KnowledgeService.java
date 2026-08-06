package com.multimodalAgent.agent.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.KnowledgeChunk;
import com.multimodalAgent.agent.repository.KnowledgeChunkRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识内容写入模块。
 *
 * <p>检索通过 {@link com.multimodalAgent.agent.service.knowledge.retrieval.EvidenceRetriever}
 * 暴露，避免知识写入和检索策略继续堆叠在同一个 interface 后面。</p>
 */
@Service
public class KnowledgeService {

    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final multimodalAgentProperties properties;
    private final ChromaGateway chromaGateway;
    private final EmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;
    private final KnowledgeChunker chunker = new KnowledgeChunker();

    public KnowledgeService(
            KnowledgeChunkRepository knowledgeChunkRepository,
            multimodalAgentProperties properties,
            ChromaGateway chromaGateway,
            EmbeddingClient embeddingClient,
            ObjectMapper objectMapper
    ) {
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.properties = properties;
        this.chromaGateway = chromaGateway;
        this.embeddingClient = embeddingClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 当前仍保留原有写入行为，知识版本和持久化索引任务将在下一阶段引入。
     */
    @Transactional
    public int ingest(String source, String content) {
        List<String> chunks = chunker.chunk(
                content,
                properties.getKnowledge().getChunkSize(),
                properties.getKnowledge().getChunkOverlap());
        knowledgeChunkRepository.deleteBySource(source);
        chromaGateway.deleteSource(source);
        for (int index = 0; index < chunks.size(); index++) {
            List<Double> embedding = safeEmbedding(chunks.get(index));
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setSource(source);
            chunk.setSourceIndex(index);
            chunk.setContent(chunks.get(index));
            chunk.setEmbeddingJson(serializeEmbedding(embedding));
            KnowledgeChunk saved = knowledgeChunkRepository.save(chunk);
            chromaGateway.mirror(saved, embedding);
        }
        return chunks.size();
    }

    private List<Double> safeEmbedding(String text) {
        try {
            return embeddingClient.embed(text);
        } catch (Exception exception) {
            if (properties.getEvaluation().isEnabled()) {
                throw new IllegalStateException("Embedding API failed during evaluation.", exception);
            }
            return List.of();
        }
    }

    private String serializeEmbedding(List<Double> embedding) {
        if (embedding == null || embedding.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(embedding);
        } catch (Exception ignored) {
            return null;
        }
    }
}
