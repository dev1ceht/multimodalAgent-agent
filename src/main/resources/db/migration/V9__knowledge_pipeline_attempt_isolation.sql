ALTER TABLE knowledge_uploads
    ADD COLUMN parser_version VARCHAR(80) NULL;

ALTER TABLE knowledge_version_sections
    DROP INDEX uk_kv_section_parent_key,
    ADD UNIQUE KEY uk_kv_section_attempt_parent_key (
        knowledge_version_id,
        build_attempt_id,
        parent_key
    );
