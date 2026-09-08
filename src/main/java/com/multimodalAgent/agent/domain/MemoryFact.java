package com.multimodalAgent.agent.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "memory_facts", uniqueConstraints = @UniqueConstraint(
        name = "uk_memory_fact_source_ordinal", columnNames = {"source_message_id", "fact_ordinal"}))
public class MemoryFact {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long userId;
    @Column(nullable = false) private Long sessionId;
    @Column(name = "source_message_id", nullable = false) private Long sourceMessageId;
    @Column(name = "fact_ordinal", nullable = false) private int factOrdinal;
    @Lob @Column(nullable = false, columnDefinition = "LONGTEXT") private String content;
    @Column(nullable = false) private Instant occurredAt;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long value) { sessionId = value; }
    public Long getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(Long value) { sourceMessageId = value; }
    public int getFactOrdinal() { return factOrdinal; }
    public void setFactOrdinal(int value) { factOrdinal = value; }
    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant value) { occurredAt = value; }
}
