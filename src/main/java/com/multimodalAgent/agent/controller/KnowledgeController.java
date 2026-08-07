package com.multimodalAgent.agent.controller;

import com.multimodalAgent.agent.domain.AuditAction;
import com.multimodalAgent.agent.domain.AuditOutcome;
import com.multimodalAgent.agent.domain.AuditResourceType;
import com.multimodalAgent.agent.dto.KnowledgeIngestRequest;
import com.multimodalAgent.agent.dto.KnowledgeIngestResponse;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.service.audit.AuditLogService;
import com.multimodalAgent.agent.service.audit.AuditRequestMetadata;
import com.multimodalAgent.agent.service.knowledge.KnowledgeFileService;
import com.multimodalAgent.agent.service.knowledge.KnowledgePublicationStatus;
import com.multimodalAgent.agent.service.knowledge.KnowledgeService;
import jakarta.validation.Valid;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

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
    public KnowledgeIngestResponse ingest(
            @Valid @RequestBody KnowledgeIngestRequest request,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        try {
            int chunks = knowledgeService.ingest(request.source(), request.content());
            auditLogService.record(
                    currentUser,
                    AuditAction.KNOWLEDGE_INGEST,
                    AuditResourceType.KNOWLEDGE,
                    request.source(),
                    AuditOutcome.SUCCESS,
                    AuditRequestMetadata.from(exchange),
                    null,
                    Map.of("chunk_count", chunks));
            return new KnowledgeIngestResponse(request.source(), chunks);
        } catch (RuntimeException exception) {
            auditLogService.record(
                    currentUser,
                    AuditAction.KNOWLEDGE_INGEST,
                    AuditResourceType.KNOWLEDGE,
                    request.source(),
                    AuditOutcome.FAILURE,
                    AuditRequestMetadata.from(exchange),
                    null,
                    Map.of());
            throw exception;
        }
    }

    @GetMapping("/status")
    public KnowledgePublicationStatus status(
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        try {
            KnowledgePublicationStatus status = knowledgeService.publicationStatus();
            auditLogService.record(
                    currentUser,
                    AuditAction.KNOWLEDGE_STATUS_VIEW,
                    AuditResourceType.KNOWLEDGE,
                    "publication-status",
                    AuditOutcome.SUCCESS,
                    AuditRequestMetadata.from(exchange),
                    null,
                    Map.of("scope", "admin"));
            return status;
        } catch (RuntimeException exception) {
            auditLogService.record(
                    currentUser,
                    AuditAction.KNOWLEDGE_STATUS_VIEW,
                    AuditResourceType.KNOWLEDGE,
                    "publication-status",
                    AuditOutcome.FAILURE,
                    AuditRequestMetadata.from(exchange),
                    null,
                    Map.of());
            throw exception;
        }
    }

    @PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<KnowledgeIngestResponse> ingestFile(
            @RequestPart("file") FilePart file,
            @AuthenticationPrincipal CurrentUser currentUser,
            ServerWebExchange exchange
    ) {
        return DataBufferUtils.join(file.content())
                .map(dataBuffer -> {
                    byte[] bytes = readBytes(dataBuffer);
                    int chunks = knowledgeFileService.ingest(file.filename(), bytes);
                    return new KnowledgeIngestResponse(file.filename(), chunks);
                })
                .doOnSuccess(response -> auditLogService.record(
                        currentUser,
                        AuditAction.KNOWLEDGE_FILE_INGEST,
                        AuditResourceType.KNOWLEDGE,
                        file.filename(),
                        AuditOutcome.SUCCESS,
                        AuditRequestMetadata.from(exchange),
                        null,
                        Map.of("chunk_count", response.chunks(), "file_type", fileType(file.filename()))))
                .doOnError(error -> auditLogService.record(
                        currentUser,
                        AuditAction.KNOWLEDGE_FILE_INGEST,
                        AuditResourceType.KNOWLEDGE,
                        file.filename(),
                        AuditOutcome.FAILURE,
                        AuditRequestMetadata.from(exchange),
                        null,
                        Map.of()));
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
