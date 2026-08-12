package com.multimodalAgent.agent.controller;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.service.knowledge.KnowledgePublicationStatus;
import com.multimodalAgent.agent.service.knowledge.KnowledgeService;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
/**
 * 智能体运行状态接口。
 *
 * <p>前端用它展示当前 provider、项目模型名称、RAG 参数和模型连接模式。</p>
 */
public class AgentStatusController {

    private final multimodalAgentProperties properties;
    private final KnowledgeService knowledgeService;

    public AgentStatusController(
            multimodalAgentProperties properties,
            KnowledgeService knowledgeService
    ) {
        this.properties = properties;
        this.knowledgeService = knowledgeService;
    }

    @GetMapping("/status")
    public AgentStatusResponse status() {
        // realModelEnabled 只表示当前使用真实模型客户端，不代表业务评估一定会展示给学生。
        String provider = properties.getAi().getProvider().toLowerCase(Locale.ROOT);
        boolean realModelEnabled = "ollama".equals(provider) || "openai".equals(provider);
        KnowledgePublicationStatus publication = knowledgeService.publicationStatus();
        return new AgentStatusResponse(
                provider,
                modelName(provider),
                realModelEnabled,
                new GenerationStatus(
                        properties.getAi().getTemperature(),
                        properties.getAi().getMaxTokens(),
                        properties.getAi().getContextWindow()),
                new EmbeddingStatus(
                        properties.getEmbedding().getBaseUrl(),
                        properties.getEmbedding().getModel(),
                        properties.getEmbedding().getDimensions()),
                new RetrievalStatus(
                        properties.getKnowledge().isUseElasticsearch(),
                        properties.getKnowledge().getRetrievalMode(),
                        properties.getKnowledge().getTopK(),
                        properties.getKnowledge().getElasticsearchBaseUrl(),
                        properties.getKnowledge().getElasticsearchIndexPrefix(),
                        properties.getKnowledge().getElasticsearchActiveAlias(),
                        properties.getKnowledge().getKnnK(),
                        properties.getKnowledge().getKnnNumCandidates(),
                        properties.getKnowledge().getRrfRankWindowSize(),
                        properties.getKnowledge().getRrfRankConstant(),
                        properties.getKnowledge().isRerankEnabled(),
                        properties.getKnowledge().getRerankCandidateMultiplier(),
                        properties.getKnowledge().getRerankSemanticWeight(),
                        properties.getKnowledge().getRerankKeywordWeight(),
                        properties.getKnowledge().getMinimumEvidenceScore(),
                        properties.getKnowledge().getChunkSize(),
                        properties.getKnowledge().getChunkOverlap(),
                        properties.getKnowledge().getChunkingStrategy(),
                        properties.getKnowledge().getParentMaxSize(),
                        properties.getKnowledge().getChildMinSize(),
                        properties.getKnowledge().getChildTargetSize(),
                        properties.getKnowledge().getChildMaxSize(),
                        properties.getKnowledge().getChildOverlap(),
                        properties.getKnowledge().getEvidenceCharacterBudget()),
                new KnowledgeStatus(
                        publication.activeVersionKey(),
                        publication.activeVersionStatus() == null
                                ? null
                                : publication.activeVersionStatus().name(),
                        publication.activeActivatedAt(),
                        publication.retrievalReady()),
                realModelEnabled ? "正在使用真实大模型客户端。" : "当前为本地 mock 演示模式，不会调用大模型。"
        );
    }

    private String modelName(String provider) {
        if ("ollama".equals(provider)) {
            return properties.getAi().getOllama().getModel();
        }
        if ("openai".equals(provider)) {
            return properties.getAi().getOpenai().getModel();
        }
        return "heuristic-local";
    }

    /**
     * 前端状态栏需要的最小状态信息。
     */
    public record AgentStatusResponse(
            String provider,
            String model,
            boolean realModelEnabled,
            GenerationStatus generation,
            EmbeddingStatus embedding,
            RetrievalStatus retrieval,
            KnowledgeStatus knowledge,
            String note
    ) {
    }

    public record GenerationStatus(
            double temperature,
            int maxTokens,
            int contextWindow
    ) {
    }

    /** API keys are intentionally excluded from the runtime status snapshot. */
    public record EmbeddingStatus(
            String baseUrl,
            String model,
            int dimensions
    ) {
    }

    public record RetrievalStatus(
            boolean elasticsearchEnabled,
            String mode,
            int topK,
            String elasticsearchBaseUrl,
            String elasticsearchIndexPrefix,
            String elasticsearchActiveAlias,
            int knnK,
            int knnNumCandidates,
            int rrfRankWindowSize,
            int rrfRankConstant,
            boolean rerankEnabled,
            int rerankCandidateMultiplier,
            double rerankSemanticWeight,
            double rerankKeywordWeight,
            double minimumEvidenceScore,
            int chunkSize,
            int chunkOverlap,
            String chunkingStrategy,
            int parentMaxSize,
            int childMinSize,
            int childTargetSize,
            int childMaxSize,
            int childOverlap,
            int evidenceCharacterBudget
    ) {
    }

    public record KnowledgeStatus(
            String activeVersionKey,
            String activeVersionStatus,
            java.time.Instant activeActivatedAt,
            boolean retrievalReady
    ) {
    }
}
