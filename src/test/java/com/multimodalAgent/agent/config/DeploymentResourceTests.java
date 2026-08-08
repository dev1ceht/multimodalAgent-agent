package com.multimodalAgent.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DeploymentResourceTests {

    @Test
    void mainComposeWaitsForDatabaseAndRedisReadiness() throws IOException {
        String compose = readFile("docker-compose.yml");

        assertThat(compose)
                .contains("condition: service_healthy")
                .contains("test: [\"CMD-SHELL\", \"mysqladmin ping")
                .contains("test: [\"CMD\", \"redis-cli\", \"ping\"]")
                .contains("MYSQL_DATABASE: multimodalAgent");
    }

    @Test
    void migrationSmokeComposeUsesAnEphemeralFixedVersionMysql() throws IOException {
        String compose = readFile("docker-compose.mysql-smoke.yml");

        assertThat(compose)
                .contains("image: mysql:8.4")
                .contains("33306:3306")
                .contains("tmpfs:")
                .contains("mysqladmin ping")
                .doesNotContain("mysql-data");
    }

    @Test
    void migrationSmokeScriptChecksFlywayAndCleansItsOwnComposeProject() throws IOException {
        String script = readFile("scripts/mysql-migration-smoke.ps1");

        assertThat(script)
                .contains("docker compose")
                .contains("flyway_schema_history")
                .contains("V0", "V1", "V2", "V3")
                .contains("$versions -join \",\"")
                .contains("finally")
                .contains("down -v --remove-orphans");
    }

    @Test
    void productionRunbookDefinesFreshAndExistingDatabasePathsAndRollback() throws IOException {
        String runbook = readFile("docs/runbooks/mysql-production-rollout.md");

        assertThat(runbook)
                .contains("V0", "V1", "V2", "V3")
                .containsIgnoringCase("baseline")
                .containsIgnoringCase("backup")
                .containsIgnoringCase("rollback")
                .contains("mysql-migration-smoke.ps1");
    }

    private String readFile(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
