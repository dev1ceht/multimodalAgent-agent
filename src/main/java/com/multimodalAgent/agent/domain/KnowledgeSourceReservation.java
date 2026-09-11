package com.multimodalAgent.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** Short-lived database reservation preventing two active uploads for one source. */
@Entity
@Table(
        name = "knowledge_source_reservations",
        indexes = @Index(name = "idx_knowledge_reservation_upload", columnList = "upload_id"),
        uniqueConstraints = @UniqueConstraint(name = "uk_knowledge_reservation_source", columnNames = "source"))
public class KnowledgeSourceReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 180)
    private String source;

    @Column(name = "upload_id", nullable = false, length = 36, unique = true)
    private String uploadId;

    @Column(name = "target_document_id")
    private Long targetDocumentId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getSource() { return source; }
    public void setSource(String value) { source = value; }
    public String getUploadId() { return uploadId; }
    public void setUploadId(String value) { uploadId = value; }
    public Long getTargetDocumentId() { return targetDocumentId; }
    public void setTargetDocumentId(Long value) { targetDocumentId = value; }
    public Instant getCreatedAt() { return createdAt; }
}
