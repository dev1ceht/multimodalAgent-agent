package com.multimodalAgent.agent.controller;

import com.multimodalAgent.agent.domain.AuditAction;
import com.multimodalAgent.agent.domain.AuditOutcome;
import com.multimodalAgent.agent.domain.AuditResourceType;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.dto.KnowledgeIngestRequest;
import com.multimodalAgent.agent.dto.KnowledgeIngestResponse;
import com.multimodalAgent.agent.dto.KnowledgeDocumentUpdateRequest;
import com.multimodalAgent.agent.dto.KnowledgeUploadPage;
import com.multimodalAgent.agent.dto.KnowledgeUploadResponse;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.service.audit.AuditLogService;
import com.multimodalAgent.agent.service.audit.AuditRequestMetadata;
import com.multimodalAgent.agent.service.knowledge.KnowledgeDocumentDetails;
import com.multimodalAgent.agent.service.knowledge.KnowledgeDocumentPage;
import com.multimodalAgent.agent.service.knowledge.KnowledgeFileService;
import com.multimodalAgent.agent.service.knowledge.KnowledgePublicationStatus;
import com.multimodalAgent.agent.service.knowledge.KnowledgeService;
import com.multimodalAgent.agent.service.knowledge.KnowledgeObjectStore;
import com.multimodalAgent.agent.service.knowledge.KnowledgeUploadService;
import com.multimodalAgent.agent.service.knowledge.KnowledgeVersionSummary;
import java.io.InputStream;
import jakarta.validation.Valid;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.PooledDataBuffer;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/admin/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final KnowledgeFileService knowledgeFileService;
    private final AuditLogService auditLogService;
    private final multimodalAgentProperties properties;
    private final ObjectProvider<KnowledgeUploadService> uploadServiceProvider;

    public KnowledgeController(
            KnowledgeService knowledgeService,
            KnowledgeFileService knowledgeFileService,
            AuditLogService auditLogService,
            multimodalAgentProperties properties,
            ObjectProvider<KnowledgeUploadService> uploadServiceProvider
    ) {
        this.knowledgeService = knowledgeService;
        this.knowledgeFileService = knowledgeFileService;
        this.auditLogService = auditLogService;
        this.properties = properties;
        this.uploadServiceProvider = uploadServiceProvider;
    }

    @PostMapping
    public Mono<KnowledgeIngestResponse> ingest(
            @Valid @RequestBody KnowledgeIngestRequest request,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        return audited(
                currentUser,
                exchange,
                AuditAction.KNOWLEDGE_INGEST,
                request.source(),
                () -> {
            int chunks = knowledgeService.ingest(request.source(), request.content());
            return new KnowledgeIngestResponse(request.source(), chunks);
                },
                response -> Map.of("chunk_count", response.chunks()));
    }

    @GetMapping("/status")
    public Mono<KnowledgePublicationStatus> status(
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        return audited(
                currentUser,
                exchange,
                AuditAction.KNOWLEDGE_STATUS_VIEW,
                "publication-status",
                knowledgeService::publicationStatus,
                ignored -> Map.of("scope", "admin"));
    }

    @GetMapping("/documents")
    public Mono<KnowledgeDocumentPage> documents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        return audited(
                currentUser,
                exchange,
                AuditAction.KNOWLEDGE_LIST_VIEW,
                "documents",
                () -> knowledgeService.listDocuments(page, size),
                result -> Map.of("scope", "admin", "result_count", result.documents().size()));
    }

    @GetMapping("/documents/{documentId}")
    public Mono<KnowledgeDocumentDetails> document(
            @PathVariable Long documentId,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        return audited(
                currentUser,
                exchange,
                AuditAction.KNOWLEDGE_DOCUMENT_VIEW,
                "document:" + documentId,
                () -> knowledgeService.getDocument(documentId),
                ignored -> Map.of("scope", "admin"));
    }

    @PostMapping("/documents")
    public Mono<KnowledgeDocumentDetails> createDocument(
            @Valid @RequestBody KnowledgeIngestRequest request,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        return audited(
                currentUser,
                exchange,
                AuditAction.KNOWLEDGE_INGEST,
                request.source(),
                () -> knowledgeService.createDocument(request.source(), request.content()),
                document -> Map.of("chunk_count", document.chunkCount()));
    }

    @PutMapping("/documents/{documentId}")
    public Mono<KnowledgeDocumentDetails> updateDocument(
            @PathVariable Long documentId,
            @Valid @RequestBody KnowledgeDocumentUpdateRequest request,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        return audited(
                currentUser,
                exchange,
                AuditAction.KNOWLEDGE_UPDATE,
                "document:" + documentId,
                () -> knowledgeService.updateDocument(
                        documentId,
                        request.source(),
                        request.content(),
                        request.version()),
                document -> Map.of("chunk_count", document.chunkCount()));
    }

    @DeleteMapping("/documents/{documentId}")
    public Mono<Map<String, String>> deleteDocument(
            @PathVariable Long documentId,
            @RequestParam long version,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        return audited(
                currentUser,
                exchange,
                AuditAction.KNOWLEDGE_DELETE,
                "document:" + documentId,
                () -> Map.of(
                        "source", knowledgeService.deleteDocument(documentId, version),
                        "status", "deleted"),
                ignored -> Map.of("status", "completed"));
    }

    @GetMapping("/versions")
    public Mono<List<KnowledgeVersionSummary>> versions(
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        return audited(
                currentUser,
                exchange,
                AuditAction.KNOWLEDGE_VERSION_LIST_VIEW,
                "versions",
                knowledgeService::listVersions,
                result -> Map.of("scope", "admin", "result_count", result.size()));
    }

    @PostMapping("/versions/{versionKey}/retry")
    public Mono<KnowledgePublicationStatus> retryVersion(
            @PathVariable String versionKey,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        return audited(
                currentUser,
                exchange,
                AuditAction.KNOWLEDGE_RETRY,
                versionKey,
                () -> knowledgeService.retryVersion(versionKey),
                ignored -> Map.of("status", "indexing"));
    }

    @PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<?>> ingestFile(
            @RequestPart("file") FilePart file,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Long targetDocumentId,
            @RequestParam(required = false) Long expectedDocumentVersion,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        KnowledgeUploadService uploadService = uploadServiceProvider.getIfAvailable();
        if (!properties.getKnowledge().isKafkaMinioMode() || uploadService == null) {
            return DataBufferUtils.join(file.content())
                    .flatMap(dataBuffer -> {
                        byte[] bytes = readBytes(dataBuffer);
                        return audited(
                                currentUser,
                                exchange,
                                AuditAction.KNOWLEDGE_FILE_INGEST,
                                file.filename(),
                                () -> {
                                    int chunks = knowledgeFileService.ingest(file.filename(), bytes);
                                    return ResponseEntity.ok((Object) new KnowledgeIngestResponse(file.filename(), chunks));
                                },
                                response -> Map.of(
                                        "chunk_count", ((KnowledgeIngestResponse) response.getBody()).chunks(),
                                        "file_type", fileType(file.filename())));
                    });
        }
        String effectiveIdempotencyKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? UUID.randomUUID().toString() : idempotencyKey;
        return stage(file)
                .flatMap(staged -> audited(
                                currentUser,
                                exchange,
                                AuditAction.KNOWLEDGE_FILE_INGEST,
                                "upload:" + effectiveIdempotencyKey,
                                () -> uploadService.accept(
                                        currentUser == null ? null : currentUser.getId(),
                                        effectiveIdempotencyKey,
                                        staged,
                                        source,
                                        targetDocumentId,
                                        expectedDocumentVersion,
                                        correlationId(exchange)),
                                upload -> Map.of(
                                        "upload_id", upload.getId(),
                                        "status", upload.getStatus().name()))
                        .map(upload -> ResponseEntity.status(org.springframework.http.HttpStatus.ACCEPTED)
                                .body((Object) uploadService.toResponse(upload)))
                        .doFinally(signal -> deleteQuietly(staged.path())));
    }

    @GetMapping("/uploads/{uploadId}")
    public Mono<KnowledgeUploadResponse> uploadStatus(
            @PathVariable String uploadId,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        KnowledgeUploadService service = requireUploadService();
        return audited(currentUser, exchange, AuditAction.KNOWLEDGE_STATUS_VIEW, "upload:" + uploadId,
                () -> service.toResponse(service.get(uploadId)), ignored -> Map.of("scope", "admin"));
    }

    @GetMapping("/uploads")
    public Mono<KnowledgeUploadPage> uploads(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        KnowledgeUploadService service = requireUploadService();
        return audited(currentUser, exchange, AuditAction.KNOWLEDGE_STATUS_VIEW, "uploads",
                () -> service.list(currentUser == null ? null : currentUser.getId(), page, size),
                result -> Map.of("scope", "admin", "result_count", result.uploads().size()));
    }

    @PostMapping("/uploads/{uploadId}/retry")
    public Mono<KnowledgeUploadResponse> retryUpload(
            @PathVariable String uploadId,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        KnowledgeUploadService service = requireUploadService();
        return audited(currentUser, exchange, AuditAction.KNOWLEDGE_RETRY, "upload:" + uploadId,
                () -> service.retryParse(uploadId, "manual-retry:" + uploadId),
                upload -> Map.of("upload_id", upload.getId(), "status", upload.getStatus().name()))
                .map(service::toResponse);
    }

    @GetMapping("/uploads/{uploadId}/original")
    public Mono<ResponseEntity<Resource>> downloadOriginal(
            @PathVariable String uploadId,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        KnowledgeUploadService service = requireUploadService();
        return Mono.fromCallable(() -> {
            var upload = service.get(uploadId);
            KnowledgeObjectStore.StoredObject stored = service.openOriginal(uploadId);
            MediaType mediaType;
            try {
                mediaType = upload.getDeclaredContentType() == null
                        ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType(upload.getDeclaredContentType());
            } catch (RuntimeException ignored) {
                mediaType = MediaType.APPLICATION_OCTET_STREAM;
            }
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .contentLength(upload.getSizeBytes())
                    .header("Content-Disposition", ContentDisposition.attachment()
                            .filename(safeFilename(upload.getOriginalFilename()), java.nio.charset.StandardCharsets.UTF_8)
                            .build().toString())
                    .body((Resource) new InputStreamResource(stored.content()));
        }).subscribeOn(Schedulers.boundedElastic())
                .doOnError(ignored -> recordAudit(currentUser, exchange, AuditAction.KNOWLEDGE_DOCUMENT_VIEW,
                        "upload:" + uploadId, AuditOutcome.FAILURE, Map.of()))
                .doOnSuccess(ignored -> recordAudit(currentUser, exchange, AuditAction.KNOWLEDGE_DOCUMENT_VIEW,
                        "upload:" + uploadId, AuditOutcome.SUCCESS, Map.of("scope", "admin")));
    }

    private KnowledgeUploadService requireUploadService() {
        KnowledgeUploadService service = uploadServiceProvider.getIfAvailable();
        if (service == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Knowledge upload API requires kafka-minio ingestion mode");
        }
        return service;
    }

    private Mono<KnowledgeUploadService.StagedUpload> stage(FilePart file) {
        return Mono.fromCallable(() -> {
            Path directory = properties.getKnowledge().getUpload().getTempDirectory() == null
                    || properties.getKnowledge().getUpload().getTempDirectory().isBlank()
                    ? Path.of(System.getProperty("java.io.tmpdir"), "mindcare-knowledge")
                    : Path.of(properties.getKnowledge().getUpload().getTempDirectory());
            Files.createDirectories(directory);
            return Files.createTempFile(directory, "knowledge-", ".upload");
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(path -> {
            AtomicLong size = new AtomicLong();
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (Exception exception) {
                deleteQuietly(path);
                return Mono.error(exception);
            }
            Flux<DataBuffer> source = file.content()
                    .map(buffer -> {
                        long total = size.addAndGet(buffer.readableByteCount());
                        if (total > properties.getKnowledge().getUpload().getMaxFileBytes()) {
                            DataBufferUtils.release(buffer);
                            throw new org.springframework.web.server.ResponseStatusException(
                                    org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                                    "Knowledge file exceeds the configured size limit");
                        }
                        ByteBuffer bytes = buffer.asByteBuffer().duplicate();
                        digest.update(bytes);
                        byte[] copy = new byte[bytes.remaining()];
                        bytes.rewind();
                        bytes.get(copy);
                        DataBufferUtils.release(buffer);
                        return org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance.wrap(copy);
                    })
                    .doOnDiscard(PooledDataBuffer.class, DataBufferUtils::release);
            return DataBufferUtils.write(source, path)
                    .then(Mono.fromCallable(() -> {
                        if (size.get() <= 0) {
                            throw new org.springframework.web.server.ResponseStatusException(
                                    org.springframework.http.HttpStatus.BAD_REQUEST, "Uploaded file is empty");
                        }
                        return new KnowledgeUploadService.StagedUpload(
                                path,
                                size.get(),
                                HexFormat.of().formatHex(digest.digest()),
                                file.filename(),
                                file.headers().getContentType() == null
                                        ? null : file.headers().getContentType().toString());
                    }))
                    .doOnError(ignored -> deleteQuietly(path));
        });
    }

    private String correlationId(ServerWebExchange exchange) {
        String trace = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        return trace == null || trace.isBlank() ? UUID.randomUUID().toString() : trace;
    }

    private String safeFilename(String filename) {
        String safe = filename == null || filename.isBlank() ? "knowledge-original" : filename;
        safe = safe.replaceAll("[\\\\/\"\r\n]+", "-");
        return safe.length() > 180 ? safe.substring(safe.length() - 180) : safe;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Temporary upload cleanup is best effort; no path is exposed to the client.
        }
    }

    private <T> Mono<T> audited(
            CurrentUser currentUser,
            ServerWebExchange exchange,
            AuditAction action,
            String resource,
            Supplier<T> operation,
            Function<T, Map<String, ?>> successDetails
    ) {
        return Mono.fromCallable(() -> {
            try {
                T result = operation.get();
                recordAudit(
                        currentUser,
                        exchange,
                        action,
                        resource,
                        AuditOutcome.SUCCESS,
                        successDetails.apply(result));
                return result;
            } catch (RuntimeException exception) {
                recordAudit(
                        currentUser,
                        exchange,
                        action,
                        resource,
                        AuditOutcome.FAILURE,
                        Map.of());
                throw exception;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void recordAudit(
            CurrentUser currentUser,
            ServerWebExchange exchange,
            AuditAction action,
            String resource,
            AuditOutcome outcome,
            Map<String, ?> details
    ) {
        auditLogService.record(
                currentUser,
                action,
                AuditResourceType.KNOWLEDGE,
                resource,
                outcome,
                AuditRequestMetadata.from(exchange),
                null,
                details);
    }

    private String fileType(String filename) {
        String normalized = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".pdf")) {
            return "pdf";
        }
        if (normalized.endsWith(".md") || normalized.endsWith(".markdown")) {
            return "markdown";
        }
        if (normalized.endsWith(".txt")) {
            return "txt";
        }
        return "unknown";
    }

    private byte[] readBytes(DataBuffer dataBuffer) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(dataBuffer.readableByteCount());
            dataBuffer.asInputStream().transferTo(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot read uploaded knowledge file", exception);
        } finally {
            DataBufferUtils.release(dataBuffer);
        }
    }
}
