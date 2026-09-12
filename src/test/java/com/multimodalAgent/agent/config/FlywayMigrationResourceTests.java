package com.multimodalAgent.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class FlywayMigrationResourceTests {

    @Test
    void versionZeroContainsTheCompleteBootstrapSchemaBeforeIncrementalChanges() throws IOException {
        String migration = read("db/migration/V0__create_schema.sql");

        assertThat(migration)
                .contains(
                        "CREATE TABLE user_accounts",
                        "CREATE TABLE psychological_reports",
                        "CREATE TABLE risk_cases",
                        "CREATE TABLE risk_case_referrals",
                        "CREATE TABLE knowledge_versions",
                        "CREATE TABLE knowledge_index_tasks");
        assertThat(migration).doesNotContain("sla_due_at");
        assertThat(tableDefinition(migration, "risk_cases")).doesNotContain("version BIGINT");
        assertThat(tableDefinition(migration, "risk_case_referrals")).doesNotContain("version BIGINT");
    }

    @Test
    void versionTwoRemainsTheIncrementalConcurrencyAndSlaChange() throws IOException {
        String migration = read("db/migration/V2__risk_case_sla_and_optimistic_locking.sql");

        assertThat(migration)
                .contains("ALTER TABLE risk_cases", "ALTER TABLE risk_case_referrals", "sla_due_at");
    }

    @Test
    void versionThreeAddsDurableOverdueEscalationMetadataAndDeliveryOwnership() throws IOException {
        String migration = read("db/migration/V3__risk_case_overdue_escalation.sql");

        assertThat(migration)
                .contains("ALTER TABLE risk_cases", "overdue_escalated_at")
                .contains("ALTER TABLE delivery_tasks", "risk_case_id")
                .contains("fk_delivery_task_risk_case");
    }

    @Test
    void versionFourAddsKnowledgeDocumentOptimisticLocking() throws IOException {
        String migration = read("db/migration/V4__knowledge_document_optimistic_locking.sql");

        assertThat(migration)
                .contains("ALTER TABLE knowledge_documents")
                .contains("version BIGINT NOT NULL DEFAULT 0");
    }

    @Test
    void versionFiveAddsHierarchicalKnowledgeChunksWithoutInvalidatingLegacyRows() throws IOException {
        String migration = read("db/migration/V5__hierarchical_knowledge_chunks.sql");

        assertThat(migration)
                .contains("CREATE TABLE knowledge_version_sections")
                .contains("ALTER TABLE knowledge_version_chunks")
                .contains("parent_section_id", "search_text", "child_index")
                .contains("ALTER TABLE knowledge_versions", "chunking_strategy");
        assertThat(migration).doesNotContain("parent_section_id BIGINT NOT NULL");
    }

    @Test
    void versionSixAddsDurableFactsTopicsAndEightRelationMemory() throws IOException {
        String migration = read("db/migration/V6__facts_topics_long_term_memory.sql");

        assertThat(migration).contains(
                "CREATE TABLE long_term_memory_tasks",
                "CREATE TABLE memory_facts",
                "CREATE TABLE memory_topics",
                "CREATE TABLE memory_fact_topics",
                "CREATE TABLE memory_relations",
                "uk_long_term_memory_task_message",
                "idx_memory_task_lease",
                "compilation_json LONGTEXT",
                "projection_revision BIGINT",
                "idx_memory_fact_user_time");
    }

    @Test
    void versionSevenAddsPrivacySafeAgentRunAndToolLifecycleMetadata() throws IOException {
        String migration = read("db/migration/V7__agent_run_metadata.sql");

        assertThat(migration)
                .contains("CREATE TABLE agent_runs", "run_id VARCHAR(36)", "schema_version VARCHAR(80)")
                .contains("CREATE TABLE agent_tool_executions", "tool_call_id VARCHAR(80)")
                .contains("uk_agent_tool_execution_call", "policy_enforced BOOLEAN")
                .doesNotContain("prompt", "arguments", "raw_result", "reasoning");
    }

    @Test
    void versionEightAddsKafkaMinioKnowledgePipelineState() throws IOException {
        String migration = read("db/migration/V8__knowledge_kafka_minio_pipeline.sql");

        assertThat(migration)
                .contains("ALTER TABLE knowledge_documents", "raw_upload_id")
                .contains("CREATE TABLE knowledge_uploads", "client_idempotency_key VARCHAR(200)")
                .contains("CREATE TABLE knowledge_outbox_events", "uk_knowledge_outbox_aggregate_generation")
                .contains("CREATE TABLE knowledge_inbox_events", "payload_hash")
                .contains("CREATE TABLE knowledge_source_reservations", "uk_knowledge_reservation_source")
                .contains("CREATE TABLE knowledge_build_attempts", "collection_name")
                .contains("CREATE TABLE knowledge_publication_locks", "VALUES (1, 0)");
    }

    @Test
    void versionNinePersistsParserVersionAndIsolatesHierarchicalBuildAttempts() throws IOException {
        String migration = read("db/migration/V9__knowledge_pipeline_attempt_isolation.sql");

        assertThat(migration)
                .contains("parser_version VARCHAR(80)")
                .contains("DROP INDEX uk_kv_section_parent_key")
                .contains("uk_kv_section_attempt_parent_key")
                .contains("build_attempt_scope", "COALESCE(build_attempt_id, 'legacy')", "parent_key");
    }
    @Test
    void versionOneRemainsAnImmutableBaselineMarker() throws IOException {
        String migration = read("db/migration/V1__baseline_existing_schema.sql");

        assertThat(migration).contains("Existing installations were created by Hibernate");
        assertThat(migration).doesNotContain("CREATE TABLE");
        assertThat(migration.trim()).isEqualTo(
                "-- Existing installations were created by Hibernate before Flyway was introduced.\n"
                        + "-- The MySQL profile baselines those tables at version 1 before applying V2.");
    }

    private String read(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String tableDefinition(String migration, String tableName) {
        String startMarker = "CREATE TABLE " + tableName;
        int start = migration.indexOf(startMarker);
        int end = migration.indexOf("CREATE TABLE ", start + startMarker.length());
        return migration.substring(start, end < 0 ? migration.length() : end);
    }
}
