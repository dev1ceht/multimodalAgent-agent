package com.multimodalAgent.agent.domain;

import com.multimodalAgent.agent.service.memory.MemoryRelationType;
import jakarta.persistence.*;

@Entity
@Table(name = "memory_relations", uniqueConstraints = @UniqueConstraint(
        name = "uk_memory_relation", columnNames = {"source_fact_id", "target_fact_id", "relation_type"}))
public class MemoryRelation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long userId;
    @Column(name = "source_fact_id", nullable = false) private Long sourceFactId;
    @Column(name = "target_fact_id", nullable = false) private Long targetFactId;
    @Enumerated(EnumType.STRING) @Column(name = "relation_type", nullable = false, length = 32)
    private MemoryRelationType type;
    @Column(nullable = false) private double confidence;
    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public Long getSourceFactId() { return sourceFactId; }
    public void setSourceFactId(Long value) { sourceFactId = value; }
    public Long getTargetFactId() { return targetFactId; }
    public void setTargetFactId(Long value) { targetFactId = value; }
    public MemoryRelationType getType() { return type; }
    public void setType(MemoryRelationType value) { type = value; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double value) { confidence = value; }
}
