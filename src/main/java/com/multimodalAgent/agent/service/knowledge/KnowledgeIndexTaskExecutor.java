package com.multimodalAgent.agent.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.KnowledgeIndexTask;
import com.multimodalAgent.agent.domain.KnowledgeIndexTaskStatus;
import com.multimodalAgent.agent.domain.KnowledgeVersion;
import com.multimodalAgent.agent.domain.KnowledgeVersionChunk;
import com.multimodalAgent.agent.domain.KnowledgeVersionDocument;
import com.multimodalAgent.agent.domain.KnowledgeVersionStatus;
import com.multimodalAgent.agent.repository.KnowledgeIndexTaskRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionChunkRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionDocumentRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionRepository;
import com.multimodalAgent.agent.service.knowledge.retrieval.RetrievalMode;
import com.multimodalAgent.agent.service.observability.OperationalMetrics;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 持久化知识索引任务执行模块。
 *
 * <p>任务领取和状态更新使用短事务；Embedding、分块和 Chroma HTTP 调用在事务之外执行。
 * 版本只有在全部投影完成后才会切换为 ACTIVE。</p>
 */
@Component
public class KnowledgeIndexTaskExecutor {

    private final KnowledgeIndexTaskRepository taskRepository;
    private final KnowledgeVersionRepository versionRepository;
    private final KnowledgeVersionDocumentRepository documentRepository;
    private final KnowledgeVersionChunkRepository chunkRepository;
    private final EmbeddingClient embeddingClient;
    private final ChromaGateway chromaGateway;
    private final ElasticsearchGateway elasticsearchGateway;
    private final multimodalAgentProperties properties;
    private final ObjectMapper objectMapper;
    private final OperationalMetrics operationalMetrics;
    private final KnowledgeChunker chunker = new KnowledgeChunker();
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean draining = new AtomicBoolean();

    public KnowledgeIndexTaskExecutor(
            KnowledgeIndexTaskRepository taskRepository,
            KnowledgeVersionRepository versionRepository,
            KnowledgeVersionDocumentRepository documentRepository,
            KnowledgeVersionChunkRepository chunkRepository,
            EmbeddingClient embeddingClient,
            ChromaGateway chromaGateway,
            ElasticsearchGateway elasticsearchGateway,
            multimodalAgentProperties properties,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            OperationalMetrics operationalMetrics
    ) {
        this.taskRepository = taskRepository;
        this.versionRepository = versionRepository;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.embeddingClient = embeddingClient;
        this.chromaGateway = chromaGateway;
        this.elasticsearchGateway = elasticsearchGateway;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.operationalMetrics = operationalMetrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${multimodal-agent.knowledge.index-sync.poll-interval-ms:1000}")
    public void pollDueTasks() {
        if (!properties.getKnowledge().getIndexSync().isEnabled()
                || !draining.compareAndSet(false, true)) {
            return;
        }
        try {
            Instant now = Instant.now();
            int batchSize = Math.max(1, properties.getKnowledge().getIndexSync().getBatchSize());
            List<KnowledgeIndexTask> candidates = new ArrayList<>();
            PageRequest page = PageRequest.of(0, batchSize);
            candidates.addAll(taskRepository
                    .findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                            KnowledgeIndexTaskStatus.PENDING, now, page));
            candidates.addAll(taskRepository
                    .findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                            KnowledgeIndexTaskStatus.RETRY_WAIT, now, page));
            candidates.addAll(taskRepository
                    .findByStatusAndLeaseUntilLessThanEqualOrderByCreatedAtAsc(
                            KnowledgeIndexTaskStatus.PROCESSING, now, page));
            for (KnowledgeIndexTask candidate : candidates) {
                Claim claim = claim(candidate.getId());
                if (claim != null) {
                    process(claim);
                }
            }
        } finally {
            draining.set(false);
        }
    }

    private Claim claim(Long taskId) {
        return transactionTemplate.execute(status -> {
            KnowledgeIndexTask task = taskRepository.findById(taskId).orElse(null);
            if (task == null || task.getStatus() == KnowledgeIndexTaskStatus.SUCCEEDED
                    || task.getStatus() == KnowledgeIndexTaskStatus.FAILED) {
                return null;
            }
            Instant now = Instant.now();
            boolean expired = task.getStatus() == KnowledgeIndexTaskStatus.PROCESSING
                    && task.getLeaseUntil() != null
                    && !task.getLeaseUntil().isAfter(now);
            if (task.getStatus() == KnowledgeIndexTaskStatus.PROCESSING && !expired) {
                return null;
            }
            String leaseToken = UUID.randomUUID().toString();
            task.incrementAttempts();
            task.setStatus(KnowledgeIndexTaskStatus.PROCESSING);
            task.setNextAttemptAt(now);
            task.setLeaseUntil(now.plusSeconds(Math.max(
                    1,
                    properties.getKnowledge().getIndexSync().getLeaseSeconds())));
            task.setLeaseToken(leaseToken);
            taskRepository.saveAndFlush(task);
            return new Claim(task.getId(), task.getKnowledgeVersionId(), leaseToken);
        });
    }

    private void process(Claim claim) {
        long started = System.nanoTime();
        try {
            buildVersion(claim);
            boolean completed = complete(claim);
            operationalMetrics.recordIndexTask(
                    completed ? "succeeded" : "lease_lost",
                    completed ? "" : "lease lost before completion",
                    System.nanoTime() - started);
        } catch (Exception exception) {
            KnowledgeIndexTaskStatus status = fail(claim, exception);
            operationalMetrics.recordIndexTask(
                    status == null ? "lease_lost" : indexOutcome(status),
                    exception.getMessage(),
                    System.nanoTime() - started);
        }
    }

    private void buildVersion(Claim claim) {
        Long versionId = claim.versionId();
        KnowledgeVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalStateException("Knowledge version not found: " + versionId));
        if (version.getStatus() == KnowledgeVersionStatus.SUPERSEDED
                || version.getStatus() == KnowledgeVersionStatus.FAILED) {
            return;
        }
        // The external index and ACTIVE database state may be committed just before the worker
        // crashes while completing its task row. A lease retry must not rebuild the live index.
        if (version.getStatus() == KnowledgeVersionStatus.ACTIVE) {
            return;
        }
        RetrievalMode mode = RetrievalMode.parse(properties.getKnowledge().getRetrievalMode());
        if (mode == RetrievalMode.CHROMA_REQUIRED && !properties.getKnowledge().isUseChroma()) {
            throw new IllegalStateException("Knowledge version requires Chroma, but Chroma is disabled.");
        }
        if (mode == RetrievalMode.ELASTICSEARCH_REQUIRED
                && !properties.getKnowledge().isUseElasticsearch()) {
            throw new IllegalStateException(
                    "Knowledge version requires Elasticsearch, but Elasticsearch is disabled.");
        }
        boolean requiresEmbedding = mode == RetrievalMode.CHROMA_REQUIRED
                || mode == RetrievalMode.ELASTICSEARCH_REQUIRED;
        if (requiresEmbedding) {
            String runtimeEmbeddingModel = embeddingClient.modelName();
            if (!version.getEmbeddingModel().equals(runtimeEmbeddingModel)) {
                throw new IllegalStateException("Embedding model does not match knowledge version: expected "
                        + version.getEmbeddingModel() + ", actual " + runtimeEmbeddingModel);
            }
        }

        if (mode == RetrievalMode.ELASTICSEARCH_REQUIRED) {
            elasticsearchGateway.prepareVersionIndex(
                    version.getCollectionName(),
                    version.getEmbeddingDimensions());
        }

        resetChunks(versionId);
        int chunkCount = 0;
        Set<String> indexedSources = new LinkedHashSet<>();
        for (KnowledgeVersionDocument document : documentRepository
                .findByKnowledgeVersionIdOrderBySourceAsc(versionId)) {
            indexedSources.add(document.getSource());
            List<String> chunks = chunker.chunk(
                    document.getContent(),
                    version.getChunkSize(),
                    version.getChunkOverlap());
            for (int index = 0; index < chunks.size(); index++) {
                String content = chunks.get(index);
                List<Double> embedding = requiresEmbedding
                        ? embeddingClient.embed(content)
                        : List.of();
                if (requiresEmbedding
                        && (embedding == null || embedding.isEmpty())) {
                    throw new IllegalStateException("Embedding is unavailable while building knowledge version.");
                }
                if (requiresEmbedding
                        && embedding.size() != version.getEmbeddingDimensions()) {
                    throw new IllegalStateException("Embedding dimensions do not match knowledge version: expected "
                            + version.getEmbeddingDimensions() + ", actual " + embedding.size());
                }
                KnowledgeVersionChunk chunk = new KnowledgeVersionChunk();
                chunk.setKnowledgeVersionId(versionId);
                chunk.setVectorId(vectorId(version.getVersionKey(), document.getSource(), index));
                chunk.setSource(document.getSource());
                chunk.setSourceIndex(index);
                chunk.setContent(content);
                chunk.setEmbeddingJson(serializeEmbedding(embedding));
                KnowledgeVersionChunk saved = saveChunk(chunk);

                if (mode == RetrievalMode.CHROMA_REQUIRED) {
                    chromaGateway.mirrorVersionChunk(
                            version.getCollectionName(),
                            saved.getVectorId(),
                            saved.getId(),
                            versionId,
                            saved.getSource(),
                            saved.getSourceIndex(),
                            saved.getContent(),
                            embedding);
                }
                if (mode == RetrievalMode.ELASTICSEARCH_REQUIRED) {
                    elasticsearchGateway.indexVersionChunk(
                            version.getCollectionName(),
                            saved.getVectorId(),
                            saved.getId(),
                            version.getVersionKey(),
                            saved.getSource(),
                            saved.getSourceIndex(),
                            saved.getContent(),
                            embedding);
                }
                chunkCount++;
            }
        }
        if (indexedSources.size() != version.getSourceCount()) {
            throw new IllegalStateException(
                    "Indexed source count does not match knowledge version: expected "
                            + version.getSourceCount() + ", actual " + indexedSources.size());
        }
        if (mode == RetrievalMode.ELASTICSEARCH_REQUIRED) {
            long indexedCount = elasticsearchGateway.refreshAndCount(version.getCollectionName());
            if (indexedCount != chunkCount) {
                throw new IllegalStateException(
                        "Elasticsearch index count does not match knowledge version: expected "
                                + chunkCount + ", actual " + indexedCount);
            }
            if (!isPublishable(claim)) {
                markReadyAndActivate(claim, chunkCount);
                return;
            }
            elasticsearchGateway.activateAlias(
                    version.getCollectionName(),
                    properties.getKnowledge().getElasticsearchActiveAlias());
        }
        markReadyAndActivate(claim, chunkCount);
    }

    private boolean isPublishable(Claim claim) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            KnowledgeIndexTask task = ownedTask(claim);
            if (task == null || task.getLeaseUntil() == null
                    || !task.getLeaseUntil().isAfter(Instant.now())) {
                return false;
            }
            KnowledgeVersion latest = versionRepository.findTopByOrderByCreatedAtDesc().orElse(null);
            return latest != null && latest.getId().equals(claim.versionId());
        }));
    }

    private void resetChunks(Long versionId) {
        transactionTemplate.executeWithoutResult(status -> chunkRepository.deleteByKnowledgeVersionId(versionId));
    }

    private KnowledgeVersionChunk saveChunk(KnowledgeVersionChunk chunk) {
        return transactionTemplate.execute(status -> chunkRepository.saveAndFlush(chunk));
    }

    private void markReadyAndActivate(Claim claim, int chunkCount) {
        transactionTemplate.executeWithoutResult(status -> {
            KnowledgeIndexTask task = ownedTask(claim);
            if (task == null
                    || task.getLeaseUntil() == null
                    || !task.getLeaseUntil().isAfter(Instant.now())) {
                throw new IllegalStateException("Knowledge index task lease lost before activation.");
            }
            Long versionId = claim.versionId();
            KnowledgeVersion version = versionRepository.findById(versionId)
                    .orElseThrow(() -> new IllegalStateException("Knowledge version not found: " + versionId));
            KnowledgeVersion latest = versionRepository.findTopByOrderByCreatedAtDesc().orElse(version);
            if (!latest.getId().equals(versionId)) {
                version.markSuperseded();
                versionRepository.save(version);
                return;
            }
            for (KnowledgeVersion active : versionRepository.findByStatus(KnowledgeVersionStatus.ACTIVE)) {
                if (!active.getId().equals(versionId)) {
                    active.markSuperseded();
                    versionRepository.save(active);
                }
            }
            version.markReady(chunkCount);
            version.markActive();
            versionRepository.save(version);
        });
    }

    private boolean complete(Claim claim) {
        Boolean completed = transactionTemplate.execute(status -> {
            KnowledgeIndexTask task = ownedTask(claim);
            if (task == null) {
                return false;
            }
            task.setStatus(KnowledgeIndexTaskStatus.SUCCEEDED);
            task.setLeaseUntil(null);
            task.setLeaseToken(null);
            task.setCompletedAt(Instant.now());
            task.setLastError(null);
            taskRepository.save(task);
            return true;
        });
        return Boolean.TRUE.equals(completed);
    }

    private KnowledgeIndexTaskStatus fail(Claim claim, Exception exception) {
        return transactionTemplate.execute(status -> {
            KnowledgeIndexTask task = ownedTask(claim);
            if (task == null) {
                return null;
            }
            String error = shorten(exception);
            task.setLastError(error);
            int maxAttempts = Math.max(1, properties.getKnowledge().getIndexSync().getMaxAttempts());
            if (task.getAttempts() >= maxAttempts) {
                task.setStatus(KnowledgeIndexTaskStatus.FAILED);
                task.setLeaseUntil(null);
                task.setLeaseToken(null);
                task.setCompletedAt(Instant.now());
                versionRepository.findById(task.getKnowledgeVersionId())
                        .ifPresent(version -> {
                            version.markFailed(error);
                            versionRepository.save(version);
                        });
            } else {
                task.setStatus(KnowledgeIndexTaskStatus.RETRY_WAIT);
                task.setLeaseUntil(null);
                task.setLeaseToken(null);
                task.setNextAttemptAt(Instant.now().plusSeconds(retryDelaySeconds(task.getAttempts())));
            }
            taskRepository.save(task);
            return task.getStatus();
        });
    }

    private String indexOutcome(KnowledgeIndexTaskStatus status) {
        return switch (status) {
            case RETRY_WAIT -> "retry_wait";
            case FAILED -> "failed";
            case SUCCEEDED -> "succeeded";
            default -> "unknown";
        };
    }

    private KnowledgeIndexTask ownedTask(Claim claim) {
        KnowledgeIndexTask task = taskRepository.findById(claim.taskId()).orElse(null);
        if (task == null || task.getStatus() != KnowledgeIndexTaskStatus.PROCESSING) {
            return null;
        }
        return claim.leaseToken().equals(task.getLeaseToken()) ? task : null;
    }

    private long retryDelaySeconds(int attempts) {
        long base = Math.max(1, properties.getKnowledge().getIndexSync().getBaseRetryDelaySeconds());
        return Math.min(3600, base * (1L << Math.min(8, Math.max(0, attempts - 1))));
    }

    private String vectorId(String versionKey, String source, int sourceIndex) {
        return versionKey + ":" + sha256(source) + ":" + sourceIndex;
    }

    private String serializeEmbedding(List<Double> embedding) {
        if (embedding == null || embedding.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(embedding);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot serialize knowledge embedding.", exception);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot hash knowledge source.", exception);
        }
    }

    private String shorten(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private record Claim(Long taskId, Long versionId, String leaseToken) {
    }
}
