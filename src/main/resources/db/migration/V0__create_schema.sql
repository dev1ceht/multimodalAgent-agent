-- Version 0 is the complete schema for a fresh MySQL database.
-- Existing non-empty installations are baselined at version 1 and skip this body.

CREATE TABLE user_accounts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(80) NOT NULL,
    password VARCHAR(255) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_account_username (username)
) ENGINE=InnoDB;

CREATE TABLE user_account_roles (
    user_id BIGINT NOT NULL,
    role VARCHAR(255) NOT NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_account_roles_user
        FOREIGN KEY (user_id) REFERENCES user_accounts (id)
) ENGINE=InnoDB;

CREATE TABLE departments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    parent_id BIGINT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_department_code (code),
    CONSTRAINT fk_department_parent
        FOREIGN KEY (parent_id) REFERENCES departments (id)
) ENGINE=InnoDB;

CREATE TABLE majors (
    id BIGINT NOT NULL AUTO_INCREMENT,
    department_id BIGINT NOT NULL,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_major_department_code (department_id, code),
    CONSTRAINT fk_major_department
        FOREIGN KEY (department_id) REFERENCES departments (id)
) ENGINE=InnoDB;

CREATE TABLE student_classes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    major_id BIGINT NOT NULL,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    grade_year INT NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_class_major_code (major_id, code),
    CONSTRAINT fk_student_class_major
        FOREIGN KEY (major_id) REFERENCES majors (id)
) ENGINE=InnoDB;

CREATE TABLE student_profiles (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    student_no VARCHAR(80) NOT NULL,
    department_id BIGINT NOT NULL,
    major_id BIGINT NOT NULL,
    class_id BIGINT NOT NULL,
    grade_year INT NOT NULL,
    gender VARCHAR(20) NULL,
    phone VARCHAR(128) NULL,
    emergency_contact_masked VARCHAR(128) NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_student_profile_user (user_id),
    UNIQUE KEY uk_student_profile_student_no (student_no),
    CONSTRAINT fk_student_profile_user
        FOREIGN KEY (user_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_student_profile_department
        FOREIGN KEY (department_id) REFERENCES departments (id),
    CONSTRAINT fk_student_profile_major
        FOREIGN KEY (major_id) REFERENCES majors (id),
    CONSTRAINT fk_student_profile_class
        FOREIGN KEY (class_id) REFERENCES student_classes (id)
) ENGINE=InnoDB;

CREATE TABLE counselor_assignments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    counselor_user_id BIGINT NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    department_id BIGINT NULL,
    major_id BIGINT NULL,
    class_id BIGINT NULL,
    grade_year INT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_assignment_counselor
        FOREIGN KEY (counselor_user_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_assignment_department
        FOREIGN KEY (department_id) REFERENCES departments (id),
    CONSTRAINT fk_assignment_major
        FOREIGN KEY (major_id) REFERENCES majors (id),
    CONSTRAINT fk_assignment_class
        FOREIGN KEY (class_id) REFERENCES student_classes (id)
) ENGINE=InnoDB;

CREATE TABLE consent_records (
    id BIGINT NOT NULL AUTO_INCREMENT,
    student_user_id BIGINT NOT NULL,
    consent_type VARCHAR(40) NOT NULL,
    version VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    granted_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_consent_student
        FOREIGN KEY (student_user_id) REFERENCES user_accounts (id)
) ENGINE=InnoDB;

CREATE TABLE chat_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_session_public_id (public_id),
    CONSTRAINT fk_chat_session_user
        FOREIGN KEY (user_id) REFERENCES user_accounts (id)
) ENGINE=InnoDB;

CREATE TABLE chat_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_chat_message_session
        FOREIGN KEY (session_id) REFERENCES chat_sessions (id),
    CONSTRAINT fk_chat_message_user
        FOREIGN KEY (user_id) REFERENCES user_accounts (id)
) ENGINE=InnoDB;

CREATE TABLE psychological_reports (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_id BIGINT NULL,
    content LONGTEXT NOT NULL,
    needs_rag BOOLEAN NOT NULL DEFAULT TRUE,
    intent VARCHAR(20) NOT NULL,
    emotion VARCHAR(20) NOT NULL,
    emotion_score DOUBLE NOT NULL,
    risk_level VARCHAR(20) NOT NULL,
    confidence DOUBLE NOT NULL,
    summary VARCHAR(500) NULL,
    emotion_tags LONGTEXT NULL,
    excel_status VARCHAR(20) NOT NULL,
    email_status VARCHAR(20) NOT NULL,
    tool_error VARCHAR(500) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_report_user
        FOREIGN KEY (user_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_report_session
        FOREIGN KEY (session_id) REFERENCES chat_sessions (id)
) ENGINE=InnoDB;

CREATE TABLE alert_records (
    id BIGINT NOT NULL AUTO_INCREMENT,
    report_id BIGINT NOT NULL,
    recipient VARCHAR(240) NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_message VARCHAR(500) NULL,
    attempts INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_alert_report
        FOREIGN KEY (report_id) REFERENCES psychological_reports (id)
) ENGINE=InnoDB;

CREATE TABLE delivery_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    report_id BIGINT NOT NULL,
    alert_record_id BIGINT NULL,
    task_type VARCHAR(32) NOT NULL,
    status VARCHAR(20) NOT NULL,
    recipient VARCHAR(240) NULL,
    idempotency_key VARCHAR(240) NOT NULL,
    attempts INT NOT NULL,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    lease_until TIMESTAMP(6) NULL,
    lease_token VARCHAR(64) NULL,
    last_error VARCHAR(500) NULL,
    completed_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_delivery_task_idempotency (idempotency_key),
    UNIQUE KEY uk_delivery_task_alert (alert_record_id),
    KEY idx_delivery_task_due (status, next_attempt_at),
    KEY idx_delivery_task_lease (status, lease_until),
    KEY idx_delivery_task_report (report_id),
    CONSTRAINT fk_delivery_task_report
        FOREIGN KEY (report_id) REFERENCES psychological_reports (id),
    CONSTRAINT fk_delivery_task_alert
        FOREIGN KEY (alert_record_id) REFERENCES alert_records (id)
) ENGINE=InnoDB;

CREATE TABLE notification_records (
    id BIGINT NOT NULL AUTO_INCREMENT,
    delivery_task_id BIGINT NOT NULL,
    channel VARCHAR(32) NOT NULL,
    recipient_type VARCHAR(32) NOT NULL,
    recipient_masked VARCHAR(240) NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_message VARCHAR(500) NULL,
    idempotency_key VARCHAR(240) NOT NULL,
    attempt_number INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_record_task_attempt (delivery_task_id, attempt_number),
    KEY idx_notification_record_task (delivery_task_id),
    KEY idx_notification_record_status (status, created_at),
    KEY idx_notification_record_key (idempotency_key),
    CONSTRAINT fk_notification_record_task
        FOREIGN KEY (delivery_task_id) REFERENCES delivery_tasks (id)
) ENGINE=InnoDB;

CREATE TABLE audit_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_user_id BIGINT NULL,
    student_user_id BIGINT NULL,
    actor_username VARCHAR(80) NOT NULL,
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_ref VARCHAR(64) NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    reason VARCHAR(240) NULL,
    request_id VARCHAR(64) NOT NULL,
    ip_address VARCHAR(64) NULL,
    user_agent VARCHAR(300) NULL,
    details VARCHAR(240) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_log_actor_created (actor_user_id, created_at),
    KEY idx_audit_log_action_created (action, created_at),
    KEY idx_audit_log_request_id (request_id)
) ENGINE=InnoDB;

CREATE TABLE risk_cases (
    id BIGINT NOT NULL AUTO_INCREMENT,
    trigger_report_id BIGINT NOT NULL,
    student_user_id BIGINT NOT NULL,
    risk_level VARCHAR(20) NOT NULL,
    source VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    opening_reason VARCHAR(240) NOT NULL,
    opened_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    acknowledged_at TIMESTAMP(6) NULL,
    resolved_at TIMESTAMP(6) NULL,
    closed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_risk_case_trigger_report (trigger_report_id),
    KEY idx_risk_case_student_updated (student_user_id, updated_at),
    KEY idx_risk_case_status_updated (status, updated_at),
    CONSTRAINT fk_risk_case_trigger_report
        FOREIGN KEY (trigger_report_id) REFERENCES psychological_reports (id),
    CONSTRAINT fk_risk_case_student
        FOREIGN KEY (student_user_id) REFERENCES user_accounts (id)
) ENGINE=InnoDB;

CREATE TABLE risk_case_referrals (
    id BIGINT NOT NULL AUTO_INCREMENT,
    risk_case_id BIGINT NOT NULL,
    created_by_user_id BIGINT NOT NULL,
    target_user_id BIGINT NULL,
    target_type VARCHAR(32) NOT NULL,
    status VARCHAR(20) NOT NULL,
    reason VARCHAR(240) NOT NULL,
    due_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    KEY idx_referral_case_created (risk_case_id, created_at),
    KEY idx_referral_target_status (target_user_id, status),
    CONSTRAINT fk_referral_case
        FOREIGN KEY (risk_case_id) REFERENCES risk_cases (id),
    CONSTRAINT fk_referral_created_by
        FOREIGN KEY (created_by_user_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_referral_target
        FOREIGN KEY (target_user_id) REFERENCES user_accounts (id)
) ENGINE=InnoDB;

CREATE TABLE risk_case_interventions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    risk_case_id BIGINT NOT NULL,
    created_by_user_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    notes VARCHAR(4000) NOT NULL,
    outcome VARCHAR(500) NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    follow_up_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_intervention_case_occurred (risk_case_id, occurred_at),
    CONSTRAINT fk_intervention_case
        FOREIGN KEY (risk_case_id) REFERENCES risk_cases (id),
    CONSTRAINT fk_intervention_created_by
        FOREIGN KEY (created_by_user_id) REFERENCES user_accounts (id)
) ENGINE=InnoDB;

CREATE TABLE knowledge_documents (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source VARCHAR(180) NOT NULL,
    content LONGTEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY idx_knowledge_document_source (source)
) ENGINE=InnoDB;

CREATE TABLE knowledge_versions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version_key VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    embedding_model VARCHAR(120) NOT NULL,
    embedding_dimensions INT NOT NULL,
    chunk_size INT NOT NULL,
    chunk_overlap INT NOT NULL,
    collection_name VARCHAR(120) NOT NULL,
    source_count INT NOT NULL,
    chunk_count INT NOT NULL,
    last_error VARCHAR(500) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    activated_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_version_key (version_key),
    UNIQUE KEY uk_knowledge_version_collection (collection_name),
    KEY idx_knowledge_version_status (status),
    KEY idx_knowledge_version_created (created_at)
) ENGINE=InnoDB;

CREATE TABLE knowledge_version_documents (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_version_id BIGINT NOT NULL,
    source VARCHAR(180) NOT NULL,
    content LONGTEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kv_document_source (knowledge_version_id, source),
    KEY idx_kv_document_version (knowledge_version_id)
) ENGINE=InnoDB;

CREATE TABLE knowledge_chunks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source VARCHAR(180) NOT NULL,
    source_index INT NOT NULL DEFAULT 0,
    content LONGTEXT NOT NULL,
    embedding_json LONGTEXT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE knowledge_version_chunks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_version_id BIGINT NOT NULL,
    vector_id VARCHAR(240) NOT NULL,
    source VARCHAR(180) NOT NULL,
    source_index INT NOT NULL,
    content LONGTEXT NOT NULL,
    embedding_json LONGTEXT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kv_chunk_vector_id (vector_id),
    KEY idx_kv_chunk_version (knowledge_version_id),
    KEY idx_kv_chunk_source (knowledge_version_id, source, source_index)
) ENGINE=InnoDB;

CREATE TABLE knowledge_index_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_version_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    attempts INT NOT NULL,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    lease_until TIMESTAMP(6) NULL,
    lease_token VARCHAR(64) NULL,
    last_error VARCHAR(500) NULL,
    completed_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_index_task_idempotency (idempotency_key),
    KEY idx_knowledge_index_due (status, next_attempt_at),
    KEY idx_knowledge_index_lease (status, lease_until)
) ENGINE=InnoDB;
