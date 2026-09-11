package com.multimodalAgent.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Singleton row used as the database-level publication lock for complete snapshots. */
@Entity
@Table(name = "knowledge_publication_locks")
public class KnowledgePublicationLock {

    @Id
    private Long id = 1L;

    @Column(nullable = false)
    private long version;

    public Long getId() { return id; }
    public long getVersion() { return version; }
}
