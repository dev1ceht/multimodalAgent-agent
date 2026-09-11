ALTER TABLE knowledge_documents
    ADD COLUMN raw_upload_id VARCHAR(36) NULL;

ALTER TABLE knowledge_version_documents
    ADD COLUMN raw_upload_id VARCHAR(36) NULL;

ALTER TABLE knowledge_versions
    ADD COLUMN active_build_attempt_id VARCHAR(36) NULL;

ALTER TABLE knowledge_index_tasks
    ADD COLUMN dispatch_generation BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN build_attempt_id VARCHAR(36) NULL,
    ADD KEY idx_knowledge_index_generation (knowledge_version_id, dispatch_generation);

ALTER TABLE knowledge_version_chunks
    ADD COLUMN build_attempt_id VARCHAR(36) NULL,
    ADD KEY idx_kv_chunk_version_attempt (knowledge_version_id, build_attempt_id);

ALTER TABLE knowledge_version_sections
    ADD COLUMN build_attempt_id VARCHAR(36) NULL,
    ADD KEY idx_kv_section_version_attempt (knowledge_version_id, build_attempt_id);

CREATE TABLE knowledge_uploads (
    id VARCHAR(36) NOT NULL,
    uploaded_by BIGINT NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    declared_content_type VARCHAR(180) NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    bucket VARCHAR(120) NOT NULL,
    object_key VARCHAR(240) NOT NULL,
    object_version_id VARCHAR(255) NULL,
    source VARCHAR(180) NOT NULL,
    target_document_id BIGINT NULL,
    expected_document_version BIGINT NULL,
    request_hash VARCHAR(64) NOT NULL,
    client_idempotency_key VARCHAR(180) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    lease_token VARCHAR(64) NULL,
    lease_until TIMESTAMP(6) NULL,
    dispatch_generation BIGINT NOT NULL DEFAULT 1,
    last_error_code VARCHAR(80) NULL,
    last_error_message VARCHAR(500) NULL,
    linked_document_id BIGINT NULL,
    knowledge_version_id BIGINT NULL,
    index_task_id BIGINT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_knowledge_upload_idempotency UNIQUE (uploaded_by, client_idempotency_key),
    INDEX idx_knowledge_upload_status_due (status, next_attempt_at),
    INDEX idx_knowledge_upload_lease (status, lease_until),
    INDEX idx_knowledge_upload_source (source),
    INDEX idx_knowledge_upload_sha (sha256)
) ENGINE=InnoDB;

CREATE TABLE knowledge_outbox_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    partition_key VARCHAR(180) NOT NULL,
    dispatch_generation BIGINT NOT NULL,
    schema_version INT NOT NULL DEFAULT 1,
    payload_json LONGTEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    lease_token VARCHAR(64) NULL,
    lease_until TIMESTAMP(6) NULL,
    published_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_knowledge_outbox_event UNIQUE (event_id),
    CONSTRAINT uk_knowledge_outbox_aggregate_generation UNIQUE (event_type, aggregate_id, dispatch_generation),
    INDEX idx_knowledge_outbox_due (status, next_attempt_at),
    INDEX idx_knowledge_outbox_lease (status, lease_until)
) ENGINE=InnoDB;

CREATE TABLE knowledge_inbox_events (
    event_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    dispatch_generation BIGINT NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    lease_token VARCHAR(64) NULL,
    lease_until TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (event_id),
    INDEX idx_knowledge_inbox_queued (status, lease_until, updated_at),
    INDEX idx_knowledge_inbox_aggregate (aggregate_id)
) ENGINE=InnoDB;

CREATE TABLE knowledge_source_reservations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source VARCHAR(180) NOT NULL,
    upload_id VARCHAR(36) NOT NULL,
    target_document_id BIGINT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_knowledge_reservation_source UNIQUE (source),
    CONSTRAINT uk_knowledge_reservation_upload UNIQUE (upload_id),
    INDEX idx_knowledge_reservation_upload (upload_id)
) ENGINE=InnoDB;

CREATE TABLE knowledge_build_attempts (
    build_attempt_id VARCHAR(36) NOT NULL,
    task_id BIGINT NOT NULL,
    knowledge_version_id BIGINT NOT NULL,
    dispatch_generation BIGINT NOT NULL,
    collection_name VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (build_attempt_id),
    CONSTRAINT uk_knowledge_build_collection UNIQUE (collection_name),
    INDEX idx_knowledge_build_attempt_task (task_id, status),
    INDEX idx_knowledge_build_attempt_version (knowledge_version_id, dispatch_generation)
) ENGINE=InnoDB;

CREATE TABLE knowledge_publication_locks (
    id BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

INSERT INTO knowledge_publication_locks (id, version)
VALUES (1, 0);
