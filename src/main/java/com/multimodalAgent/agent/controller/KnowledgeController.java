package com.multimodalAgent.agent.controller;

import com.multimodalAgent.agent.domain.AuditAction;
import com.multimodalAgent.agent.domain.AuditOutcome;
import com.multimodalAgent.agent.domain.AuditResourceType;
import com.multimodalAgent.agent.dto.KnowledgeIngestRequest;
import com.multimodalAgent.agent.dto.KnowledgeIngestResponse;
import com.multimodalAgent.agent.dto.KnowledgeDocumentUpdateRequest;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.service.audit.AuditLogService;
import com.multimodalAgent.agent.service.audit.AuditRequestMetadata;
import com.multimodalAgent.agent.service.knowledge.KnowledgeDocumentDetails;
import com.multimodalAgent.agent.service.knowledge.KnowledgeDocumentPage;
import com.multimodalAgent.agent.service.knowledge.KnowledgeFileService;
import com.multimodalAgent.agent.service.knowledge.KnowledgePublicationStatus;
import com.multimodalAgent.agent.service.knowledge.KnowledgeService;
import com.multimodalAgent.agent.service.knowledge.KnowledgeVersionSummary;
import jakarta.validation.Valid;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/admin/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final KnowledgeFileService knowledgeFileService;
    private final AuditLogService auditLogService;

    public KnowledgeController(
            KnowledgeService knowledgeService,
            KnowledgeFileService knowledgeFileService,
            AuditLogService auditLogService
    ) {
        this.knowledgeService = knowledgeService;
        this.knowledgeFileService = knowledgeFileService;
        this.auditLogService = auditLogService;
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
    public Mono<KnowledgeIngestResponse> ingestFile(
            @RequestPart("file") FilePart file,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
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
                                return new KnowledgeIngestResponse(file.filename(), chunks);
                            },
                            response -> Map.of(
                                    "chunk_count", response.chunks(),
                                    "file_type", fileType(file.filename())));
                });
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
