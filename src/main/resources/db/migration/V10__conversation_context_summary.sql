CREATE TABLE conversation_context_summaries (
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    covered_through_message_id BIGINT NOT NULL DEFAULT 0,
    summary_json LONGTEXT NOT NULL,
    token_count INT NOT NULL DEFAULT 0,
    token_counter_version VARCHAR(64) NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    model_key VARCHAR(160) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (session_id),
    CONSTRAINT fk_context_summary_session
        FOREIGN KEY (session_id) REFERENCES chat_sessions (id),
    CONSTRAINT fk_context_summary_user
        FOREIGN KEY (user_id) REFERENCES user_accounts (id)
) ENGINE=InnoDB;

CREATE TABLE conversation_context_jobs (
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    desired_through_message_id BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    lease_until TIMESTAMP(6) NULL,
    lease_token VARCHAR(36) NULL,
    claimed_base_version BIGINT NULL,
    claimed_target_id BIGINT NULL,
    last_error_code VARCHAR(160) NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (session_id),
    CONSTRAINT fk_context_job_session
        FOREIGN KEY (session_id) REFERENCES chat_sessions (id),
    CONSTRAINT fk_context_job_user
        FOREIGN KEY (user_id) REFERENCES user_accounts (id),
    INDEX idx_context_job_due (status, next_attempt_at),
    INDEX idx_context_job_lease (status, lease_until)
) ENGINE=InnoDB;

ALTER TABLE chat_messages
    ADD INDEX idx_chat_message_session_id_id (session_id, id);
