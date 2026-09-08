CREATE TABLE long_term_memory_tasks (
    id VARCHAR(36) NOT NULL,
    source_message_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    session_public_id VARCHAR(64) NOT NULL,
    role VARCHAR(20) NOT NULL,
    content LONGTEXT NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    lease_until TIMESTAMP(6) NULL,
    lease_token VARCHAR(36) NULL,
    last_error VARCHAR(1000) NULL,
    compilation_json LONGTEXT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_long_term_memory_task_message UNIQUE (source_message_id),
    INDEX idx_memory_task_due (status, next_attempt_at),
    INDEX idx_memory_task_lease (status, lease_until)
);

CREATE TABLE memory_facts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    source_message_id BIGINT NOT NULL,
    fact_ordinal INT NOT NULL,
    content LONGTEXT NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_memory_fact_source_ordinal UNIQUE (source_message_id, fact_ordinal),
    INDEX idx_memory_fact_user_time (user_id, occurred_at),
    INDEX idx_memory_fact_session_time (user_id, session_id, occurred_at)
);

CREATE TABLE memory_topics (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    topic_key VARCHAR(160) NOT NULL,
    title VARCHAR(255) NOT NULL,
    summary TEXT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_memory_topic_user_key UNIQUE (user_id, topic_key)
);

CREATE TABLE memory_fact_topics (
    id BIGINT NOT NULL AUTO_INCREMENT,
    fact_id BIGINT NOT NULL,
    topic_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_memory_fact_topic UNIQUE (fact_id, topic_id),
    INDEX idx_memory_fact_topic_topic (topic_id)
);

CREATE TABLE memory_relations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    source_fact_id BIGINT NOT NULL,
    target_fact_id BIGINT NOT NULL,
    relation_type VARCHAR(32) NOT NULL,
    confidence DOUBLE NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_memory_relation UNIQUE (source_fact_id, target_fact_id, relation_type),
    INDEX idx_memory_relation_user_source (user_id, source_fact_id),
    INDEX idx_memory_relation_user_target (user_id, target_fact_id)
);
