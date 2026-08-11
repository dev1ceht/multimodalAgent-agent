package com.multimodalAgent.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** 一组知识文档及其索引配置组成的不可变知识库版本。 */
@Entity
@Table(
        name = "knowledge_versions",
        indexes = {
                @Index(name = "idx_knowledge_version_status", columnList = "status"),
                @Index(name = "idx_knowledge_version_created", columnList = "created_at")
        })
public class KnowledgeVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version_key", nullable = false, unique = true, length = 64)
    private String versionKey = UUID.randomUUID().toString().replace("-", "");

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KnowledgeVersionStatus status = KnowledgeVersionStatus.BUILDING;

    @Column(name = "embedding_model", nullable = false, length = 120)
    private String embeddingModel;

    @Column(name = "embedding_dimensions", nullable = false)
    private int embeddingDimensions;

    @Column(name = "chunk_size", nullable = false)
    private int chunkSize;

    @Column(name = "chunk_overlap", nullable = false)
    private int chunkOverlap;

    @Column(name = "collection_name", nullable = false, unique = true, length = 120)
    private String collectionName;

    @Column(name = "source_count", nullable = false)
    private int sourceCount;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "activated_at")
    private Instant activatedAt;

    public Long getId() {
        return id;
    }

    public String getVersionKey() {
        return versionKey;
    }

    public KnowledgeVersionStatus getStatus() {
        return status;
    }

    public void setStatus(KnowledgeVersionStatus status) {
        this.status = status;
        touch();
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
        touch();
    }

    public int getEmbeddingDimensions() {
        return embeddingDimensions;
    }

    public void setEmbeddingDimensions(int embeddingDimensions) {
        this.embeddingDimensions = embeddingDimensions;
        touch();
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
        touch();
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
        touch();
    }

    public String getCollectionName() {
        return collectionName;
    }

    public void setCollectionName(String collectionName) {
        this.collectionName = collectionName;
        touch();
    }

    public int getSourceCount() {
        return sourceCount;
    }

    public void setSourceCount(int sourceCount) {
        this.sourceCount = sourceCount;
        touch();
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(int chunkCount) {
        this.chunkCount = chunkCount;
        touch();
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
        touch();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public void markActive() {
        status = KnowledgeVersionStatus.ACTIVE;
        activatedAt = Instant.now();
        lastError = null;
        touch();
    }

    public void markReady(int chunkCount) {
        status = KnowledgeVersionStatus.READY;
        this.chunkCount = chunkCount;
        lastError = null;
        touch();
    }

    public void markFailed(String error) {
        status = KnowledgeVersionStatus.FAILED;
        lastError = error;
        touch();
    }

    public void markBuilding() {
        status = KnowledgeVersionStatus.BUILDING;
        lastError = null;
        touch();
    }

    public void markSuperseded() {
        status = KnowledgeVersionStatus.SUPERSEDED;
        touch();
    }

    private void touch() {
        updatedAt = Instant.now();
    }
}
