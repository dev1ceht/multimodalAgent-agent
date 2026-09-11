package com.multimodalAgent.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Durable metadata and state for one administrator-supplied knowledge object. */
@Entity
@Table(
        name = "knowledge_uploads",
        indexes = {
                @Index(name = "idx_knowledge_upload_status_due", columnList = "status,next_attempt_at"),
                @Index(name = "idx_knowledge_upload_lease", columnList = "status,lease_until"),
                @Index(name = "idx_knowledge_upload_created", columnList = "created_at")
        })
public class KnowledgeUpload {

    @Id
    @Column(length = 36, updatable = false)
    private String id = UUID.randomUUID().toString();

    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "declared_content_type", length = 180)
    private String declaredContentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false, length = 120)
    private String bucket;

    @Column(name = "object_key", nullable = false, length = 255)
    private String objectKey;

    @Column(name = "object_version_id", length = 255)
    private String objectVersionId;

    @Column(nullable = false, length = 180)
    private String source;

    @Column(name = "target_document_id")
    private Long targetDocumentId;

    @Column(name = "expected_document_version")
    private Long expectedDocumentVersion;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "client_idempotency_key", nullable = false, length = 200)
    private String clientIdempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private KnowledgeUploadStatus status = KnowledgeUploadStatus.STORING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "lease_token", length = 64)
    private String leaseToken;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "dispatch_generation", nullable = false)
    private long dispatchGeneration = 1;

    @Column(name = "last_error_code", length = 80)
    private String lastErrorCode;

    @Column(name = "last_error_message", length = 500)
    private String lastErrorMessage;

    @Column(name = "linked_document_id")
    private Long linkedDocumentId;

    @Column(name = "knowledge_version_id")
    private Long knowledgeVersionId;

    @Column(name = "index_task_id")
    private Long indexTaskId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    public String getId() { return id; }
    public Long getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(Long value) { uploadedBy = value; touch(); }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String value) { originalFilename = value; touch(); }
    public String getDeclaredContentType() { return declaredContentType; }
    public void setDeclaredContentType(String value) { declaredContentType = value; touch(); }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long value) { sizeBytes = value; touch(); }
    public String getSha256() { return sha256; }
    public void setSha256(String value) { sha256 = value; touch(); }
    public String getBucket() { return bucket; }
    public void setBucket(String value) { bucket = value; touch(); }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String value) { objectKey = value; touch(); }
    public String getObjectVersionId() { return objectVersionId; }
    public void setObjectVersionId(String value) { objectVersionId = value; touch(); }
    public String getSource() { return source; }
    public void setSource(String value) { source = value; touch(); }
    public Long getTargetDocumentId() { return targetDocumentId; }
    public void setTargetDocumentId(Long value) { targetDocumentId = value; touch(); }
    public Long getExpectedDocumentVersion() { return expectedDocumentVersion; }
    public void setExpectedDocumentVersion(Long value) { expectedDocumentVersion = value; touch(); }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String value) { requestHash = value; touch(); }
    public String getClientIdempotencyKey() { return clientIdempotencyKey; }
    public void setClientIdempotencyKey(String value) { clientIdempotencyKey = value; touch(); }
    public KnowledgeUploadStatus getStatus() { return status; }
    public void setStatus(KnowledgeUploadStatus value) { status = value; touch(); }
    public int getAttempts() { return attempts; }
    public void incrementAttempts() { attempts++; touch(); }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant value) { nextAttemptAt = value; touch(); }
    public String getLeaseToken() { return leaseToken; }
    public void setLeaseToken(String value) { leaseToken = value; touch(); }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant value) { leaseUntil = value; touch(); }
    public long getDispatchGeneration() { return dispatchGeneration; }
    public void setDispatchGeneration(long value) { dispatchGeneration = value; touch(); }
    public void incrementDispatchGeneration() { dispatchGeneration++; touch(); }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String value) { lastErrorCode = value; touch(); }
    public String getLastErrorMessage() { return lastErrorMessage; }
    public void setLastErrorMessage(String value) { lastErrorMessage = value; touch(); }
    public Long getLinkedDocumentId() { return linkedDocumentId; }
    public void setLinkedDocumentId(Long value) { linkedDocumentId = value; touch(); }
    public Long getKnowledgeVersionId() { return knowledgeVersionId; }
    public void setKnowledgeVersionId(Long value) { knowledgeVersionId = value; touch(); }
    public Long getIndexTaskId() { return indexTaskId; }
    public void setIndexTaskId(Long value) { indexTaskId = value; touch(); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    public void clearLease() {
        leaseToken = null;
        leaseUntil = null;
        touch();
    }

    public void markStored(String objectVersionId) {
        status = KnowledgeUploadStatus.STORED;
        this.objectVersionId = objectVersionId;
        lastErrorCode = null;
        lastErrorMessage = null;
        clearLease();
        touch();
    }

    public void markStorageFailed(String code, String message) {
        status = KnowledgeUploadStatus.STORAGE_FAILED;
        lastErrorCode = code;
        lastErrorMessage = message;
        clearLease();
        touch();
    }

    public void markParsing() {
        status = KnowledgeUploadStatus.PARSING;
        lastErrorCode = null;
        lastErrorMessage = null;
        touch();
    }

    public void markParsed() {
        status = KnowledgeUploadStatus.PARSED;
        clearLease();
        touch();
    }

    public void markConflict(String code, String message) {
        status = KnowledgeUploadStatus.CONFLICT;
        lastErrorCode = code;
        lastErrorMessage = message;
        clearLease();
        touch();
    }

    public void markFailed(String code, String message, boolean retryable) {
        status = retryable ? KnowledgeUploadStatus.RETRY_WAIT : KnowledgeUploadStatus.FAILED;
        lastErrorCode = code;
        lastErrorMessage = message;
        clearLease();
        touch();
    }

    private void touch() { updatedAt = Instant.now(); }
}
