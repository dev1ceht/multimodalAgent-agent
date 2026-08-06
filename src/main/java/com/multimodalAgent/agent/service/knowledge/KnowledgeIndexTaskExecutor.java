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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
    private final multimodalAgentProperties properties;
    private final ObjectMapper objectMapper;
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
            multimodalAgentProperties properties,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.taskRepository = taskRepository;
        this.versionRepository = versionRepository;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.embeddingClient = embeddingClient;
        this.chromaGateway = chromaGateway;
        this.properties = properties;
        this.objectMapper = objectMapper;
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
        try {
            buildVersion(claim.versionId());
            complete(claim);
        } catch (Exception exception) {
            fail(claim, exception);
        }
    }

    private void buildVersion(Long versionId) {
        KnowledgeVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalStateException("Knowledge version not found: " + versionId));
        if (version.getStatus() == KnowledgeVersionStatus.SUPERSEDED
                || version.getStatus() == KnowledgeVersionStatus.FAILED) {
            return;
        }
        RetrievalMode mode = RetrievalMode.parse(properties.getKnowledge().getRetrievalMode());
        if (mode == RetrievalMode.CHROMA_REQUIRED && !properties.getKnowledge().isUseChroma()) {
            throw new IllegalStateException("Knowledge version requires Chroma, but Chroma is disabled.");
        }
        if (mode == RetrievalMode.CHROMA_REQUIRED) {
            String runtimeEmbeddingModel = embeddingClient.modelName();
            if (!version.getEmbeddingModel().equals(runtimeEmbeddingModel)) {
                throw new IllegalStateException("Embedding model does not match knowledge version: expected "
                        + version.getEmbeddingModel() + ", actual " + runtimeEmbeddingModel);
            }
        }

        resetChunks(versionId);
        int chunkCount = 0;
        for (KnowledgeVersionDocument document : documentRepository
                .findByKnowledgeVersionIdOrderBySourceAsc(versionId)) {
            List<String> chunks = chunker.chunk(
                    document.getContent(),
                    version.getChunkSize(),
                    version.getChunkOverlap());
            for (int index = 0; index < chunks.size(); index++) {
                String content = chunks.get(index);
                List<Double> embedding = mode == RetrievalMode.CHROMA_REQUIRED
                        ? embeddingClient.embed(content)
                        : List.of();
                if (mode == RetrievalMode.CHROMA_REQUIRED
                        && (embedding == null || embedding.isEmpty())) {
                    throw new IllegalStateException("Embedding is unavailable while building knowledge version.");
                }
                if (mode == RetrievalMode.CHROMA_REQUIRED
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
                chunkCount++;
            }
        }
        markReadyAndActivate(versionId, chunkCount);
    }

    private void resetChunks(Long versionId) {
        transactionTemplate.executeWithoutResult(status -> chunkRepository.deleteByKnowledgeVersionId(versionId));
    }

    private KnowledgeVersionChunk saveChunk(KnowledgeVersionChunk chunk) {
        return transactionTemplate.execute(status -> chunkRepository.saveAndFlush(chunk));
    }

    private void markReadyAndActivate(Long versionId, int chunkCount) {
        transactionTemplate.executeWithoutResult(status -> {
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

    private void complete(Claim claim) {
        transactionTemplate.executeWithoutResult(status -> {
            KnowledgeIndexTask task = ownedTask(claim);
            if (task == null) {
                return;
            }
            task.setStatus(KnowledgeIndexTaskStatus.SUCCEEDED);
            task.setLeaseUntil(null);
            task.setLeaseToken(null);
            task.setCompletedAt(Instant.now());
            task.setLastError(null);
            taskRepository.save(task);
        });
    }

    private void fail(Claim claim, Exception exception) {
        transactionTemplate.executeWithoutResult(status -> {
            KnowledgeIndexTask task = ownedTask(claim);
            if (task == null) {
                return;
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
        });
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
