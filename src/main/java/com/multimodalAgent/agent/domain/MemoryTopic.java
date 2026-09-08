package com.multimodalAgent.agent.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "memory_topics", uniqueConstraints = @UniqueConstraint(
        name = "uk_memory_topic_user_key", columnNames = {"user_id", "topic_key"}))
public class MemoryTopic {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "topic_key", nullable = false, length = 160) private String topicKey;
    @Column(nullable = false, length = 255) private String title;
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String summary;
    @Column(nullable = false) private long projectionRevision;
    @Column(nullable = false) private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public String getTopicKey() { return topicKey; }
    public void setTopicKey(String value) { topicKey = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getSummary() { return summary; }
    public void setSummary(String value) { summary = value; }
    public long getProjectionRevision() { return projectionRevision; }
    public void setProjectionRevision(long value) { projectionRevision = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
