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
