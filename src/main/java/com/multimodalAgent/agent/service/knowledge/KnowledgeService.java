package com.multimodalAgent.agent.service.knowledge;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.KnowledgeDocument;
import com.multimodalAgent.agent.domain.KnowledgeIndexTask;
import com.multimodalAgent.agent.domain.KnowledgeIndexTaskStatus;
import com.multimodalAgent.agent.domain.KnowledgeVersion;
import com.multimodalAgent.agent.domain.KnowledgeVersionDocument;
import com.multimodalAgent.agent.domain.KnowledgeVersionStatus;
import com.multimodalAgent.agent.repository.KnowledgeDocumentRepository;
import com.multimodalAgent.agent.repository.KnowledgeIndexTaskRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionDocumentRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionRepository;
import com.multimodalAgent.agent.service.knowledge.retrieval.RetrievalMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 知识目录写入模块。
 *
 * <p>它只在本地事务中更新 canonical 文档、不可变版本副本和索引任务，不调用 Embedding
 * 或 Elasticsearch。外部索引由持久化任务异步完成。</p>
 */
@Service
public class KnowledgeService {

    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeVersionRepository knowledgeVersionRepository;
    private final KnowledgeVersionDocumentRepository knowledgeVersionDocumentRepository;
    private final KnowledgeIndexTaskRepository knowledgeIndexTaskRepository;
    private final multimodalAgentProperties properties;
    private final KnowledgeChunker chunker;

    public KnowledgeService(
            KnowledgeDocumentRepository knowledgeDocumentRepository,
            KnowledgeVersionRepository knowledgeVersionRepository,
            KnowledgeVersionDocumentRepository knowledgeVersionDocumentRepository,
            KnowledgeIndexTaskRepository knowledgeIndexTaskRepository,
            multimodalAgentProperties properties,
            KnowledgeChunker chunker
    ) {
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.knowledgeVersionRepository = knowledgeVersionRepository;
        this.knowledgeVersionDocumentRepository = knowledgeVersionDocumentRepository;
        this.knowledgeIndexTaskRepository = knowledgeIndexTaskRepository;
        this.properties = properties;
        this.chunker = chunker;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public int ingest(String source, String content) {
        return ingestBatch(List.of(new KnowledgeDocumentInput(source, content)));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public int ingestBatch(List<KnowledgeDocumentInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return 0;
        }

        boolean changed = false;
        int chunkCount = 0;
        for (KnowledgeDocumentInput input : inputs) {
            chunkCount += chunker.plan(input, currentChunkingPolicy()).children().size();
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
                .findTopByOrderByCreatedAtDescIdDesc()
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

    @Transactional(readOnly = true)
    public KnowledgeDocumentPage listDocuments(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Knowledge document page must be non-negative and size must be between 1 and 100");
        }
        var result = knowledgeDocumentRepository.findDocumentSummaries(
                PageRequest.of(page, size, Sort.by("source").ascending()));
        List<KnowledgeDocumentSummary> documents = result.getContent().stream()
                .map(view -> new KnowledgeDocumentSummary(
                        view.getId(),
                        view.getSource(),
                        view.getCharacterCount(),
                        preview(view.getPreview()),
                        view.getVersion()))
                .toList();
        return new KnowledgeDocumentPage(
                documents,
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public KnowledgeDocumentDetails getDocument(Long documentId) {
        return toDetails(requireDocument(documentId));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public KnowledgeDocumentDetails createDocument(String source, String content) {
        String normalizedSource = normalizeSource(source);
        String normalizedContent = requireContent(content);
        if (knowledgeDocumentRepository.findBySource(normalizedSource).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Knowledge source already exists: " + normalizedSource);
        }
        KnowledgeDocument document = new KnowledgeDocument();
        document.setSource(normalizedSource);
        document.setContent(normalizedContent);
        document.setContentHash(sha256(normalizedContent));
        knowledgeDocumentRepository.saveAndFlush(document);
        createVersionAndIndexTask();
        return toDetails(document);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public KnowledgeDocumentDetails updateDocument(
            Long documentId,
            String source,
            String content,
            long expectedVersion
    ) {
        KnowledgeDocument document = requireDocument(documentId);
        if (document.getVersion() != expectedVersion) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Knowledge document was updated by another administrator");
        }
        String normalizedSource = normalizeSource(source);
        String normalizedContent = requireContent(content);
        knowledgeDocumentRepository.findBySource(normalizedSource)
                .filter(existing -> !existing.getId().equals(documentId))
                .ifPresent(existing -> {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Knowledge source already exists: " + normalizedSource);
                });
        String contentHash = sha256(normalizedContent);
        boolean changed = !normalizedSource.equals(document.getSource())
                || !contentHash.equals(document.getContentHash());
        if (changed) {
            document.setSource(normalizedSource);
            document.setContent(normalizedContent);
            document.setContentHash(contentHash);
            knowledgeDocumentRepository.saveAndFlush(document);
            createVersionAndIndexTask();
        }
        return toDetails(document);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public String deleteDocument(Long documentId, long expectedVersion) {
        KnowledgeDocument document = requireDocument(documentId);
        if (document.getVersion() != expectedVersion) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Knowledge document was updated by another administrator");
        }
        String source = document.getSource();
        knowledgeDocumentRepository.delete(document);
        knowledgeDocumentRepository.flush();
        createVersionAndIndexTask();
        return source;
    }

    @Transactional(readOnly = true)
    public List<KnowledgeVersionSummary> listVersions() {
        List<KnowledgeVersion> versions = knowledgeVersionRepository.findTop20ByOrderByCreatedAtDescIdDesc();
        Long latestId = versions.isEmpty() ? null : versions.get(0).getId();
        return versions.stream()
                .map(version -> toVersionSummary(version, version.getId().equals(latestId)))
                .toList();
    }

    @Transactional
    public KnowledgePublicationStatus retryVersion(String versionKey) {
        KnowledgeVersion version = knowledgeVersionRepository.findByVersionKey(versionKey)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Knowledge version not found"));
        KnowledgeVersion latest = knowledgeVersionRepository.findTopByOrderByCreatedAtDescIdDesc()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Knowledge version not found"));
        if (!latest.getId().equals(version.getId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only the latest knowledge version can be retried");
        }
        KnowledgeIndexTask task = knowledgeIndexTaskRepository
                .findTopByKnowledgeVersionIdOrderByCreatedAtDesc(version.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Knowledge version has no index task"));
        if (version.getStatus() != KnowledgeVersionStatus.FAILED
                || task.getStatus() != KnowledgeIndexTaskStatus.FAILED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only a failed knowledge version can be retried");
        }
        version.markBuilding();
        task.resetForManualRetry();
        knowledgeVersionRepository.save(version);
        knowledgeIndexTaskRepository.save(task);
        return publicationStatus();
    }

    private void createVersionAndIndexTask() {
        knowledgeVersionRepository.findByStatus(KnowledgeVersionStatus.BUILDING)
                .forEach(KnowledgeVersion::markSuperseded);

        KnowledgeVersion version = new KnowledgeVersion();
        version.setEmbeddingModel(properties.getEmbedding().getModel());
        version.setEmbeddingDimensions(properties.getEmbedding().getDimensions());
        version.setChunkSize(properties.getKnowledge().getChunkSize());
        version.setChunkOverlap(properties.getKnowledge().getChunkOverlap());
        ChunkingPolicy policy = currentChunkingPolicy();
        version.setChunkingStrategy(policy.strategy());
        version.setParentMaxSize(policy.parentMaxSize());
        version.setChildMinSize(policy.childMinSize());
        version.setChildTargetSize(policy.childTargetSize());
        version.setChildMaxSize(policy.childMaxSize());
        version.setChildOverlap(policy.childOverlap());
        RetrievalMode.parse(properties.getKnowledge().getRetrievalMode());
        String indexPrefix = properties.getKnowledge().getElasticsearchIndexPrefix();
        version.setCollectionName(indexPrefix.toLowerCase(java.util.Locale.ROOT)
                + "-" + version.getVersionKey());

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

    private KnowledgeDocument requireDocument(Long documentId) {
        return knowledgeDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Knowledge document not found"));
    }

    private KnowledgeDocumentDetails toDetails(KnowledgeDocument document) {
        String content = document.getContent();
        return new KnowledgeDocumentDetails(
                document.getId(),
                document.getSource(),
                content,
                content.length(),
                countChunks(content),
                document.getCreatedAt(),
                document.getUpdatedAt(),
                document.getVersion());
    }

    private KnowledgeVersionSummary toVersionSummary(KnowledgeVersion version, boolean latest) {
        KnowledgeIndexTask task = knowledgeIndexTaskRepository
                .findTopByKnowledgeVersionIdOrderByCreatedAtDesc(version.getId())
                .orElse(null);
        String error = task != null && task.getLastError() != null
                ? task.getLastError()
                : version.getLastError();
        boolean failed = version.getStatus() == KnowledgeVersionStatus.FAILED
                && task != null
                && task.getStatus() == KnowledgeIndexTaskStatus.FAILED;
        return new KnowledgeVersionSummary(
                version.getVersionKey(),
                version.getStatus(),
                version.getSourceCount(),
                version.getChunkCount(),
                task == null ? null : task.getStatus(),
                task == null ? 0 : task.getAttempts(),
                error,
                version.getCreatedAt(),
                version.getActivatedAt(),
                latest,
                version.getStatus() == KnowledgeVersionStatus.ACTIVE,
                latest && failed);
    }

    private int countChunks(String content) {
        return chunker.plan(
                new KnowledgeDocumentInput("preview", content),
                currentChunkingPolicy()).children().size();
    }

    private ChunkingPolicy currentChunkingPolicy() {
        var knowledge = properties.getKnowledge();
        return new ChunkingPolicy(
                knowledge.getChunkingStrategy(),
                knowledge.getParentMaxSize(),
                knowledge.getChildMinSize(),
                knowledge.getChildTargetSize(),
                knowledge.getChildMaxSize(),
                knowledge.getChildOverlap());
    }

    private String preview(String content) {
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160) + "…";
    }

    private String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Knowledge source is required");
        }
        String normalized = source.trim();
        if (normalized.length() > 180) {
            throw new IllegalArgumentException("Knowledge source cannot exceed 180 characters");
        }
        return normalized;
    }

    private String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Knowledge content is required");
        }
        return content;
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
