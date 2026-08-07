package com.multimodalAgent.agent.service.knowledge;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.KnowledgeDocument;
import com.multimodalAgent.agent.domain.KnowledgeIndexTask;
import com.multimodalAgent.agent.domain.KnowledgeVersion;
import com.multimodalAgent.agent.domain.KnowledgeVersionDocument;
import com.multimodalAgent.agent.domain.KnowledgeVersionStatus;
import com.multimodalAgent.agent.repository.KnowledgeDocumentRepository;
import com.multimodalAgent.agent.repository.KnowledgeIndexTaskRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionDocumentRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识目录写入模块。
 *
 * <p>它只在本地事务中更新 canonical 文档、不可变版本副本和索引任务，不调用 Embedding
 * 或 Chroma。外部索引由持久化任务异步完成。</p>
 */
@Service
public class KnowledgeService {

    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeVersionRepository knowledgeVersionRepository;
    private final KnowledgeVersionDocumentRepository knowledgeVersionDocumentRepository;
    private final KnowledgeIndexTaskRepository knowledgeIndexTaskRepository;
    private final multimodalAgentProperties properties;
    private final KnowledgeChunker chunker = new KnowledgeChunker();

    public KnowledgeService(
            KnowledgeDocumentRepository knowledgeDocumentRepository,
            KnowledgeVersionRepository knowledgeVersionRepository,
            KnowledgeVersionDocumentRepository knowledgeVersionDocumentRepository,
            KnowledgeIndexTaskRepository knowledgeIndexTaskRepository,
            multimodalAgentProperties properties
    ) {
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.knowledgeVersionRepository = knowledgeVersionRepository;
        this.knowledgeVersionDocumentRepository = knowledgeVersionDocumentRepository;
        this.knowledgeIndexTaskRepository = knowledgeIndexTaskRepository;
        this.properties = properties;
    }

    @Transactional
    public int ingest(String source, String content) {
        return ingestBatch(List.of(new KnowledgeDocumentInput(source, content)));
    }

    @Transactional
    public int ingestBatch(List<KnowledgeDocumentInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return 0;
        }

        boolean changed = false;
        int chunkCount = 0;
        for (KnowledgeDocumentInput input : inputs) {
            chunkCount += chunker.chunk(
                    input.content(),
                    properties.getKnowledge().getChunkSize(),
                    properties.getKnowledge().getChunkOverlap()).size();
            String contentHash = sha256(input.content());
            KnowledgeDocument document = knowledgeDocumentRepository.findBySource(input.source())
                    .orElseGet(KnowledgeDocument::new);
            if (contentHash.equals(document.getContentHash())) {
                continue;
            }
            document.setSource(input.source());
            document.setContent(input.content());
            document.setContentHash(contentHash);
            knowledgeDocumentRepository.save(document);
            changed = true;
        }

        if (changed) {
            createVersionAndIndexTask();
        }
        return chunkCount;
    }

    @Transactional(readOnly = true)
    public KnowledgePublicationStatus publicationStatus() {
        KnowledgeVersion active = knowledgeVersionRepository
                .findTopByStatusOrderByActivatedAtDesc(KnowledgeVersionStatus.ACTIVE)
                .orElse(null);
        KnowledgeVersion latest = knowledgeVersionRepository
                .findTopByOrderByCreatedAtDesc()
                .orElse(null);
        KnowledgeIndexTask task = latest == null
                ? null
                : knowledgeIndexTaskRepository
                        .findTopByKnowledgeVersionIdOrderByCreatedAtDesc(latest.getId())
                        .orElse(null);
        return new KnowledgePublicationStatus(
                active == null ? null : active.getVersionKey(),
                active == null ? null : active.getStatus(),
                latest == null ? null : latest.getVersionKey(),
                latest == null ? null : latest.getStatus(),
                task == null ? null : task.getStatus(),
                task == null ? 0 : task.getAttempts(),
                task == null ? null : task.getLastError(),
                latest == null ? 0 : latest.getSourceCount(),
                latest == null ? 0 : latest.getChunkCount(),
                latest == null ? null : latest.getCreatedAt(),
                active == null ? null : active.getActivatedAt(),
                active != null && active.getStatus() == KnowledgeVersionStatus.ACTIVE);
    }

    private void createVersionAndIndexTask() {
        knowledgeVersionRepository.findByStatus(KnowledgeVersionStatus.BUILDING)
                .forEach(KnowledgeVersion::markSuperseded);

        KnowledgeVersion version = new KnowledgeVersion();
        version.setEmbeddingModel(properties.getEmbedding().getModel());
        version.setEmbeddingDimensions(properties.getEmbedding().getDimensions());
        version.setChunkSize(properties.getKnowledge().getChunkSize());
        version.setChunkOverlap(properties.getKnowledge().getChunkOverlap());
        version.setCollectionName(properties.getKnowledge().getChromaCollection()
                + "_" + version.getVersionKey());

        List<KnowledgeDocument> documents = knowledgeDocumentRepository.findAllByOrderBySourceAsc();
        version.setSourceCount(documents.size());
        knowledgeVersionRepository.saveAndFlush(version);

        List<KnowledgeVersionDocument> snapshot = documents.stream()
                .map(document -> {
                    KnowledgeVersionDocument copy = new KnowledgeVersionDocument();
                    copy.setKnowledgeVersionId(version.getId());
                    copy.setSource(document.getSource());
                    copy.setContent(document.getContent());
                    copy.setContentHash(document.getContentHash());
                    return copy;
                })
                .toList();
        knowledgeVersionDocumentRepository.saveAll(snapshot);

        KnowledgeIndexTask task = new KnowledgeIndexTask();
        task.setKnowledgeVersionId(version.getId());
        task.setIdempotencyKey("knowledge-version:" + version.getVersionKey());
        knowledgeIndexTaskRepository.save(task);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot hash knowledge document.", exception);
        }
    }
}
