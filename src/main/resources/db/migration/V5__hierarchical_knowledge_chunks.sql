ALTER TABLE knowledge_versions
    ADD COLUMN chunking_strategy VARCHAR(40) NOT NULL DEFAULT 'FLAT_V1',
    ADD COLUMN parent_max_size INT NULL,
    ADD COLUMN child_min_size INT NULL,
    ADD COLUMN child_target_size INT NULL,
    ADD COLUMN child_max_size INT NULL,
    ADD COLUMN child_overlap INT NULL;

CREATE TABLE knowledge_version_sections (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_version_id BIGINT NOT NULL,
    parent_key VARCHAR(64) NOT NULL,
    source VARCHAR(180) NOT NULL,
    section_index INT NOT NULL,
    section_path VARCHAR(500) NOT NULL,
    content LONGTEXT NOT NULL,
    start_offset INT NOT NULL,
    end_offset INT NOT NULL,
    page_start INT NULL,
    page_end INT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kv_section_parent_key (knowledge_version_id, parent_key),
    KEY idx_kv_section_source (knowledge_version_id, source, section_index)
) ENGINE=InnoDB;

ALTER TABLE knowledge_version_chunks
    ADD COLUMN parent_section_id BIGINT NULL,
    ADD COLUMN child_index INT NULL,
    ADD COLUMN search_text LONGTEXT NULL,
    ADD COLUMN start_offset INT NULL,
    ADD COLUMN end_offset INT NULL,
    ADD COLUMN page_start INT NULL,
    ADD COLUMN page_end INT NULL,
    ADD KEY idx_kv_chunk_parent (parent_section_id);
