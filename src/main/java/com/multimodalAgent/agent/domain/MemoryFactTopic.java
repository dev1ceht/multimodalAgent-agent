package com.multimodalAgent.agent.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "memory_fact_topics", uniqueConstraints = @UniqueConstraint(
        name = "uk_memory_fact_topic", columnNames = {"fact_id", "topic_id"}))
public class MemoryFactTopic {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "fact_id", nullable = false) private Long factId;
    @Column(name = "topic_id", nullable = false) private Long topicId;
    public Long getId() { return id; }
    public Long getFactId() { return factId; }
    public void setFactId(Long value) { factId = value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { topicId = value; }
}
