package com.multimodalAgent.agent.service.knowledge;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.KnowledgeIndexTask;
import com.multimodalAgent.agent.domain.KnowledgeIndexTaskStatus;
import com.multimodalAgent.agent.domain.KnowledgeUpload;
import com.multimodalAgent.agent.domain.KnowledgeUploadStatus;
import com.multimodalAgent.agent.domain.KnowledgeVersion;
import com.multimodalAgent.agent.domain.KnowledgeVersionStatus;
import com.multimodalAgent.agent.dto.KnowledgeUploadPage;
import com.multimodalAgent.agent.dto.KnowledgeUploadResponse;
import com.multimodalAgent.agent.repository.KnowledgeDocumentRepository;
import com.multimodalAgent.agent.repository.KnowledgeIndexTaskRepository;
import com.multimodalAgent.agent.repository.KnowledgeOutboxEventRepository;
import com.multimodalAgent.agent.repository.KnowledgeSourceReservationRepository;
import com.multimodalAgent.agent.repository.KnowledgeUploadRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionRepository;
import com.multimodalAgent.agent.service.observability.OperationalMetrics;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Durable upload lifecycle for the explicit kafka-minio ingestion mode. */
@Service
@ConditionalOnProperty(
        prefix = "multimodal-agent.knowledge",
        name = "ingestion-mode",
        havingValue = "kafka-minio")
public class KnowledgeUploadService {

    private final KnowledgeUploadRepository uploadRepository;
    private final KnowledgeSourceReservationRepository reservationRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeVersionRepository versionRepository;
    private final KnowledgeIndexTaskRepository taskRepository;
    private final KnowledgeObjectStore objectStore;
    private final KnowledgeOutboxService outboxService;
    private final multimodalAgentProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Semaphore stagedFiles;
    private final OperationalMetrics operationalMetrics;

    public KnowledgeUploadService(
            KnowledgeUploadRepository uploadRepository,
            KnowledgeSourceReservationRepository reservationRepository,
            KnowledgeDocumentRepository documentRepository,
            KnowledgeVersionRepository versionRepository,
            KnowledgeIndexTaskRepository taskRepository,
            KnowledgeObjectStore objectStore,
            KnowledgeOutboxService outboxService,
            multimodalAgentProperties properties,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            OperationalMetrics operationalMetrics
    ) {
        this.uploadRepository = uploadRepository;
        this.reservationRepository = reservationRepository;
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.taskRepository = taskRepository;
        this.objectStore = objectStore;
        this.outboxService = outboxService;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.operationalMetrics = operationalMetrics;
        this.stagedFiles = new Semaphore(Math.max(1,
                properties.getKnowledge().getUpload().getMaxConcurrentStagedFiles()));
    }

    /** Stores the original and returns only after STORED and its parse event are committed. */
    public KnowledgeUpload accept(
            Long uploadedBy,
            String idempotencyKey,
            StagedUpload staged,
            String source,
            Long targetDocumentId,
            Long expectedDocumentVersion,
            String correlationId
    ) {
        if (uploadedBy == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Administrator identity is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is required");
        }
        if (staged == null || staged.path() == null || staged.size() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded file is empty");
        }
        String normalizedSource = normalizeSource(source == null ? staged.filename() : source);
        String requestHash = hashRequest(normalizedSource, targetDocumentId, expectedDocumentVersion, staged);
        UploadReservation reservation = transactionTemplate.execute(status ->
                register(uploadedBy, idempotencyKey, normalizedSource, targetDocumentId,
                        expectedDocumentVersion, staged, requestHash));
        if (reservation == null) {
            throw new IllegalStateException("Unable to register knowledge upload");
        }
        if (reservation.existing() != null) {
            return reservation.existing();
        }

        boolean acquired = false;
        long started = System.nanoTime();
        try {
            stagedFiles.acquire();
            acquired = true;
            KnowledgeObjectStore.ObjectRef ref;
            try (InputStream input = Files.newInputStream(staged.path())) {
                ref = objectStore.put(new KnowledgeObjectStore.PutRequest(
                        reservation.upload().getBucket(),
                        reservation.upload().getObjectKey(),
                        input,
                        staged.size(),
                        staged.sha256(),
                        staged.contentType()));
            }
            if (ref.size() != staged.size()
                    || (ref.sha256() != null && !staged.sha256().equalsIgnoreCase(ref.sha256()))) {
                throw new IOException("Stored object metadata does not match uploaded bytes");
            }
            KnowledgeUpload stored = completeStorage(
                    reservation.upload().getId(), reservation.leaseToken(), ref, correlationId);
            operationalMetrics.recordKnowledgeUpload("stored", System.nanoTime() - started);
            operationalMetrics.recordKnowledgeStage("upload", "stored");
            return stored;
        } catch (Exception exception) {
            markStorageFailed(reservation.upload().getId(), reservation.leaseToken(), exception);
            operationalMetrics.recordKnowledgeUpload("failed", System.nanoTime() - started);
            operationalMetrics.recordKnowledgeStage("upload", "failed");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Knowledge original could not be stored", exception);
        } finally {
            if (acquired) {
                stagedFiles.release();
            }
        }
    }

    @Transactional(readOnly = true)
    public KnowledgeUpload get(String uploadId) {
        return uploadRepository.findById(uploadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Knowledge upload not found"));
    }

    @Transactional(readOnly = true)
    public KnowledgeUploadPage list(Long uploadedBy, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid upload page");
        }
        var result = uploadRepository.findByUploadedByOrderByCreatedAtDesc(
                uploadedBy, PageRequest.of(page, size, Sort.by("createdAt").descending()));
        return new KnowledgeUploadPage(
                result.getContent().stream().map(this::toResponse).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public KnowledgeUpload retryParse(String uploadId, String correlationId) {
        KnowledgeUpload upload = getForUpdate(uploadId);
        if (upload.getStatus() != KnowledgeUploadStatus.RETRY_WAIT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a retryable knowledge parse can be retried");
        }
        upload.incrementDispatchGeneration();
        upload.setStatus(KnowledgeUploadStatus.STORED);
        upload.setNextAttemptAt(Instant.now());
        upload.setLastErrorCode(null);
        upload.setLastErrorMessage(null);
        uploadRepository.save(upload);
        outboxService.enqueueParseRequested(upload, correlationId);
        return upload;
    }

    @Transactional
    public ClaimedUpload claimForParsing(String uploadId) {
        return claimForParsing(uploadId, -1);
    }

    @Transactional
    public ClaimedUpload claimForParsing(String uploadId, long expectedGeneration) {
        KnowledgeUpload upload = getForUpdate(uploadId);
        Instant now = Instant.now();
        if (upload.getStatus() != KnowledgeUploadStatus.STORED
                || upload.getNextAttemptAt().isAfter(now)
                || (expectedGeneration > 0 && upload.getDispatchGeneration() != expectedGeneration)) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        upload.incrementAttempts();
        upload.markParsing();
        upload.setLeaseToken(token);
        upload.setLeaseUntil(now.plusSeconds(Math.max(1,
                properties.getKnowledge().getKafka().getLeaseSeconds())));
        uploadRepository.saveAndFlush(upload);
        return new ClaimedUpload(upload.getId(), upload.getDispatchGeneration(), token,
                upload.getBucket(), upload.getObjectKey(), upload.getOriginalFilename(), upload.getSha256());
    }

    @Transactional
    public boolean markParseFailure(
            String uploadId,
            long generation,
            String leaseToken,
            String code,
            String message,
            boolean retryable,
            String correlationId
    ) {
        KnowledgeUpload upload = getForUpdate(uploadId);
        if (!owns(upload, generation, leaseToken)) {
            return false;
        }
        boolean willRetry = retryable
                && upload.getAttempts() < Math.max(1, properties.getKnowledge().getKafka().getMaxAttempts());
        String failureCode = willRetry || !retryable ? code : "MAX_PARSE_ATTEMPTS";
        String failureMessage = willRetry || !retryable
                ? shorten(message)
                : "Knowledge parsing reached the configured retry limit: " + shorten(message);
        upload.markFailed(failureCode, shorten(failureMessage), willRetry);
        if (willRetry) {
            upload.setNextAttemptAt(Instant.now().plusSeconds(retryDelay(upload.getAttempts())));
        } else {
            outboxService.enqueueDeadLetter(upload.getId(), upload.getDispatchGeneration(), correlationId);
        }
        uploadRepository.save(upload);
        return true;
    }

    public KnowledgeObjectStore.StoredObject openOriginal(String uploadId) {
        KnowledgeUpload upload = get(uploadId);
        if (upload.getStatus() == KnowledgeUploadStatus.STORING
                || upload.getStatus() == KnowledgeUploadStatus.STORAGE_FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Original is not stored");
        }
        try {
            return objectStore.get(upload.getBucket(), upload.getObjectKey());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Original is unavailable", exception);
        }
    }

    @Scheduled(fixedDelayString = "${multimodal-agent.knowledge.kafka.poll-interval-ms:1000}")
    public void recoverStoringUploads() {
        Instant now = Instant.now();
        int batchSize = Math.max(1, properties.getKnowledge().getKafka().getBatchSize());
        List<KnowledgeUpload> candidates = uploadRepository
                .findByStatusAndLeaseUntilLessThanEqualOrderByCreatedAtAsc(
                        KnowledgeUploadStatus.STORING, now, PageRequest.of(0, batchSize));
        for (KnowledgeUpload upload : candidates) {
            recoverStoring(upload.getId());
        }
    }

    public KnowledgeUploadResponse toResponse(KnowledgeUpload upload) {
        String publication = publicationStatus(upload);
        boolean retryable = upload.getStatus() == KnowledgeUploadStatus.RETRY_WAIT;
        String versionKey = upload.getKnowledgeVersionId() == null ? null
                : versionRepository.findById(upload.getKnowledgeVersionId())
                        .map(KnowledgeVersion::getVersionKey).orElse(null);
        return new KnowledgeUploadResponse(
                upload.getId(), upload.getSource(), upload.getStatus(),
                "/api/admin/knowledge/uploads/" + upload.getId(),
                upload.getLinkedDocumentId(), versionKey, publication,
                upload.getAttempts(), retryable, upload.getLastErrorCode(),
                upload.getLastErrorMessage(), upload.getSizeBytes());
    }

    private UploadReservation register(
            Long uploadedBy,
            String idempotencyKey,
            String source,
            Long targetDocumentId,
            Long expectedDocumentVersion,
            StagedUpload staged,
            String requestHash
    ) {
        KnowledgeUpload existing = uploadRepository
                .findByUploadedByAndClientIdempotencyKey(uploadedBy, idempotencyKey).orElse(null);
        if (existing != null) {
            if (!requestHash.equals(existing.getRequestHash())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Idempotency-Key was already used with different upload parameters");
            }
            if (existing.getStatus() == KnowledgeUploadStatus.STORAGE_FAILED) {
                String token = UUID.randomUUID().toString();
                existing.setStatus(KnowledgeUploadStatus.STORING);
                existing.setLeaseToken(token);
                existing.setLeaseUntil(Instant.now().plusSeconds(Math.max(1,
                        properties.getKnowledge().getUpload().getStorageLeaseSeconds())));
                uploadRepository.save(existing);
                return new UploadReservation(existing, null, token);
            }
            return new UploadReservation(existing, existing, null);
        }
        if (targetDocumentId != null) {
            var target = documentRepository.findById(targetDocumentId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "Target knowledge document does not exist"));
            if (expectedDocumentVersion == null || target.getVersion() != expectedDocumentVersion) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Target knowledge document version is stale");
            }
            documentRepository.findBySource(source)
                    .filter(document -> !document.getId().equals(targetDocumentId))
                    .ifPresent(document -> { throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Knowledge source already belongs to another document"); });
        } else if (documentRepository.findBySource(source).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Knowledge source already exists; explicitly select a replacement target");
        }
        if (reservationRepository.findBySource(source).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Knowledge source is being uploaded");
        }
        KnowledgeUpload upload = new KnowledgeUpload();
        upload.setUploadedBy(uploadedBy);
        upload.setOriginalFilename(staged.filename());
        upload.setDeclaredContentType(staged.contentType());
        upload.setSizeBytes(staged.size());
        upload.setSha256(staged.sha256());
        upload.setBucket(properties.getKnowledge().getMinio().getBucket());
        upload.setObjectKey("knowledge/raw/" + upload.getId() + "/original");
        upload.setSource(source);
        upload.setTargetDocumentId(targetDocumentId);
        upload.setExpectedDocumentVersion(expectedDocumentVersion);
        upload.setRequestHash(requestHash);
        upload.setClientIdempotencyKey(idempotencyKey);
        String token = UUID.randomUUID().toString();
        upload.setLeaseToken(token);
        upload.setLeaseUntil(Instant.now().plusSeconds(Math.max(1,
                properties.getKnowledge().getUpload().getStorageLeaseSeconds())));
        upload = uploadRepository.saveAndFlush(upload);
        var reservation = new com.multimodalAgent.agent.domain.KnowledgeSourceReservation();
        reservation.setSource(source);
        reservation.setUploadId(upload.getId());
        reservation.setTargetDocumentId(targetDocumentId);
        reservationRepository.saveAndFlush(reservation);
        return new UploadReservation(upload, null, token);
    }

    private KnowledgeUpload completeStorage(
            String uploadId,
            String leaseToken,
            KnowledgeObjectStore.ObjectRef ref,
            String correlationId
    ) {
        return transactionTemplate.execute(status -> {
            KnowledgeUpload upload = getForUpdate(uploadId);
            if (upload.getStatus() == KnowledgeUploadStatus.STORED
                    || upload.getStatus() == KnowledgeUploadStatus.PARSING
                    || upload.getStatus() == KnowledgeUploadStatus.PARSED) {
                return upload;
            }
            if (!leaseToken.equals(upload.getLeaseToken())) {
                throw new IllegalStateException("Knowledge upload storage lease was lost");
            }
            upload.markStored(ref.versionId());
            uploadRepository.save(upload);
            outboxService.enqueueParseRequested(upload, correlationId);
            return upload;
        });
    }

    private void markStorageFailed(String uploadId, String leaseToken, Exception exception) {
        transactionTemplate.executeWithoutResult(status -> {
            KnowledgeUpload upload = uploadRepository.findByIdForUpdate(uploadId).orElse(null);
            if (upload != null && (leaseToken == null || leaseToken.equals(upload.getLeaseToken()))) {
                upload.markStorageFailed("MINIO_STORE_FAILED", shorten(exception.getMessage()));
                uploadRepository.save(upload);
                reservationRepository.deleteByUploadId(uploadId);
            }
        });
    }

    private void recoverStoring(String uploadId) {
        RecoveryClaim claim = transactionTemplate.execute(status -> {
            KnowledgeUpload upload = uploadRepository.findByIdForUpdate(uploadId).orElse(null);
            if (upload == null || upload.getStatus() != KnowledgeUploadStatus.STORING
                    || upload.getLeaseUntil() == null || upload.getLeaseUntil().isAfter(Instant.now())) {
                return null;
            }
            String token = UUID.randomUUID().toString();
            upload.setLeaseToken(token);
            upload.setLeaseUntil(Instant.now().plusSeconds(Math.max(1,
                    properties.getKnowledge().getUpload().getStorageLeaseSeconds())));
            uploadRepository.saveAndFlush(upload);
            return new RecoveryClaim(upload.getId(), upload.getBucket(), upload.getObjectKey(),
                    upload.getSizeBytes(), upload.getSha256(), token);
        });
        if (claim == null) {
            return;
        }
        try {
            KnowledgeObjectStore.ObjectRef ref = verifyStoredObject(claim);
            transactionTemplate.executeWithoutResult(status -> {
                KnowledgeUpload upload = uploadRepository.findByIdForUpdate(claim.uploadId()).orElse(null);
                if (upload == null || upload.getStatus() != KnowledgeUploadStatus.STORING
                        || !claim.leaseToken().equals(upload.getLeaseToken())) {
                    return;
                }
                upload.markStored(ref.versionId());
                uploadRepository.save(upload);
                outboxService.enqueueParseRequested(upload, "storage-recovery:" + claim.uploadId());
            });
        } catch (IOException exception) {
            transactionTemplate.executeWithoutResult(status -> {
                KnowledgeUpload upload = uploadRepository.findByIdForUpdate(claim.uploadId()).orElse(null);
                if (upload != null && upload.getStatus() == KnowledgeUploadStatus.STORING
                        && claim.leaseToken().equals(upload.getLeaseToken())) {
                    upload.markStorageFailed(
                            exception.getMessage() != null && exception.getMessage().contains("metadata")
                                    ? "OBJECT_METADATA_MISMATCH" : "OBJECT_NOT_FOUND",
                            shorten(exception.getMessage()));
                    uploadRepository.save(upload);
                    reservationRepository.deleteByUploadId(claim.uploadId());
                }
            });
        }
    }

    private KnowledgeObjectStore.ObjectRef verifyStoredObject(RecoveryClaim claim) throws IOException {
        KnowledgeObjectStore.ObjectRef ref = objectStore.stat(claim.bucket(), claim.objectKey());
        if (ref.size() >= 0 && ref.size() != claim.sizeBytes()) {
            throw new IOException("Stored object metadata does not match upload size");
        }
        if (ref.sha256() != null && !claim.sha256().equalsIgnoreCase(ref.sha256())) {
            throw new IOException("Stored object metadata does not match upload hash");
        }
        if (ref.size() == claim.sizeBytes() && ref.sha256() != null) {
            return ref;
        }
        try (KnowledgeObjectStore.StoredObject stored = objectStore.get(claim.bucket(), claim.objectKey())) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stored.content().read(buffer)) != -1) {
                size += read;
                digest.update(buffer, 0, read);
            }
            String actualHash = HexFormat.of().formatHex(digest.digest());
            if (size != claim.sizeBytes() || !claim.sha256().equalsIgnoreCase(actualHash)) {
                throw new IOException("Stored object content does not match upload metadata");
            }
            return new KnowledgeObjectStore.ObjectRef(
                    ref.bucket(), ref.objectKey(), actualHash, size, ref.versionId());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IOException("Cannot verify stored object hash", exception);
        }
    }

    private KnowledgeUpload getForUpdate(String uploadId) {
        return uploadRepository.findByIdForUpdate(uploadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Knowledge upload not found"));
    }

    private boolean owns(KnowledgeUpload upload, long generation, String leaseToken) {
        return upload.getStatus() == KnowledgeUploadStatus.PARSING
                && upload.getDispatchGeneration() == generation
                && leaseToken != null && leaseToken.equals(upload.getLeaseToken());
    }

    private String publicationStatus(KnowledgeUpload upload) {
        if (upload.getStatus() != KnowledgeUploadStatus.PARSED) {
            return upload.getStatus().name();
        }
        if ("NO_CHANGE".equals(upload.getLastErrorCode())) {
            return "NO_CHANGE";
        }
        if (upload.getKnowledgeVersionId() == null) {
            return "PARSED";
        }
        KnowledgeVersion version = versionRepository.findById(upload.getKnowledgeVersionId()).orElse(null);
        KnowledgeIndexTask task = upload.getIndexTaskId() == null ? null : taskRepository.findById(upload.getIndexTaskId()).orElse(null);
        if (version == null || task == null) return "INDEXING";
        if (version.getStatus() == KnowledgeVersionStatus.ACTIVE && task.getStatus() == KnowledgeIndexTaskStatus.SUCCEEDED) return "ACTIVE";
        if (version.getStatus() == KnowledgeVersionStatus.SUPERSEDED) return "SUPERSEDED";
        if (version.getStatus() == KnowledgeVersionStatus.FAILED || task.getStatus() == KnowledgeIndexTaskStatus.FAILED) return "FAILED";
        return "INDEXING";
    }

    private String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Knowledge source is required");
        }
        String normalized = source.trim().replaceAll("[\\\\/]+", "-");
        if (normalized.length() > 180) normalized = normalized.substring(normalized.length() - 180);
        return normalized;
    }

    private String hashRequest(String source, Long targetId, Long expectedVersion, StagedUpload staged) {
        return sha256(source + "\n" + targetId + "\n" + expectedVersion + "\n"
                + staged.filename() + "\n" + staged.contentType() + "\n" + staged.size() + "\n" + staged.sha256());
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot hash knowledge upload request", exception);
        }
    }

    private long retryDelay(int attempts) {
        long base = 5;
        return Math.min(3600, base * (1L << Math.min(8, Math.max(0, attempts - 1))));
    }

    private String shorten(String message) {
        String value = message == null || message.isBlank() ? "unknown error" : message;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    public record StagedUpload(
            Path path,
            long size,
            String sha256,
            String filename,
            String contentType
    ) {
    }

    public record ClaimedUpload(
            String uploadId,
            long dispatchGeneration,
            String leaseToken,
            String bucket,
            String objectKey,
            String filename,
            String sha256
    ) {
    }

    private record UploadReservation(KnowledgeUpload upload, KnowledgeUpload existing, String leaseToken) {
    }

    private record RecoveryClaim(
            String uploadId,
            String bucket,
            String objectKey,
            long sizeBytes,
            String sha256,
            String leaseToken) {
    }
}
