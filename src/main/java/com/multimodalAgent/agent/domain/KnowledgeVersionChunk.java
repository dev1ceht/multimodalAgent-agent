package com.multimodalAgent.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 知识版本构建后的不可变检索片段。 */
@Entity
@Table(
        name = "knowledge_version_chunks",
        indexes = {
                @Index(name = "idx_kv_chunk_version", columnList = "knowledge_version_id"),
                @Index(name = "idx_kv_chunk_source", columnList = "knowledge_version_id,source,source_index")
        },
        uniqueConstraints = @UniqueConstraint(name = "uk_kv_chunk_vector_id", columnNames = "vector_id"))
public class KnowledgeVersionChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "knowledge_version_id", nullable = false)
    private Long knowledgeVersionId;

    @Column(name = "vector_id", nullable = false, length = 240)
    private String vectorId;

    @Column(nullable = false, length = 180)
    private String source;

    @Column(name = "source_index", nullable = false)
    private int sourceIndex;

    @Lob
    @Column(nullable = false)
    private String content;

    @Lob
    private String embeddingJson;

    public Long getId() {
        return id;
    }

    public Long getKnowledgeVersionId() {
        return knowledgeVersionId;
    }

    public void setKnowledgeVersionId(Long knowledgeVersionId) {
        this.knowledgeVersionId = knowledgeVersionId;
    }

    public String getVectorId() {
        return vectorId;
    }

    public void setVectorId(String vectorId) {
        this.vectorId = vectorId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public int getSourceIndex() {
        return sourceIndex;
    }

    public void setSourceIndex(int sourceIndex) {
        this.sourceIndex = sourceIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getEmbeddingJson() {
        return embeddingJson;
    }

    public void setEmbeddingJson(String embeddingJson) {
        this.embeddingJson = embeddingJson;
    }
}
