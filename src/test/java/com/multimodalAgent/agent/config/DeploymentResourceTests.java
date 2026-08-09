package com.multimodalAgent.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class DeploymentResourceTests {

    @Test
    void applicationExportsPrometheusMetricsThroughAnInternalManagementPort() throws IOException {
        String pom = readFile("pom.xml");
        String application = readFile("src/main/resources/application.yml");
        String security = readFile("src/main/java/com/multimodalAgent/agent/config/SecurityConfig.java");
        String compose = readFile("docker-compose.yml");

        assertThat(pom).contains("micrometer-registry-prometheus");
        assertThat(application)
                .contains("address: ${MANAGEMENT_SERVER_ADDRESS:127.0.0.1}")
                .contains("port: ${MANAGEMENT_SERVER_PORT:9090}")
                .contains("enabled: ${PROMETHEUS_EXPORT_ENABLED:true}")
                .contains("include: health,info,metrics,prometheus")
                .contains("probes:", "enabled: true")
                .contains("multimodalagent.http.request: true")
                .contains("multimodalagent.rag.retrieval: true");
        assertThat(security)
                .contains("/actuator/health/**")
                .contains("/actuator/prometheus");
        assertThat(compose)
                .contains("MANAGEMENT_SERVER_ADDRESS: 0.0.0.0")
                .contains("MANAGEMENT_SERVER_PORT: 9090")
                .contains("expose:", "- \"9090\"");
    }

    @Test
    void observabilityProfileScrapesRoutesAndVisualizesBoundedApplicationMetrics() throws IOException {
        String compose = readFile("docker-compose.yml");
        String prometheus = readFile("observability/prometheus/prometheus.yml");
        String rules = readFile("observability/prometheus/rules/multimodalagent-alerts.yml");
        String alertmanager = readFile("observability/alertmanager/alertmanager.yml");
        String datasource = readFile("observability/grafana/provisioning/datasources/prometheus.yml");
        String dashboard = readFile("observability/grafana/dashboards/multimodalagent-overview.json");

        assertThat(compose)
                .contains("profiles: [\"observability\"]")
                .contains("prom/prometheus:v3.13.1")
                .contains("prom/alertmanager:v0.33.1")
                .contains("grafana/grafana:13.1.0")
                .contains("127.0.0.1:3000:3000")
                .contains("127.0.0.1:9090:9090")
                .contains("127.0.0.1:9093:9093")
                .contains("127.0.0.1:8025:8025")
                .doesNotContain("--web.enable-lifecycle");
        assertThat(prometheus)
                .contains("app:9090")
                .contains("/actuator/prometheus")
                .contains("alertmanager:9093")
                .contains("rules/*.yml");
        assertThat(rules)
                .contains("MultimodalAgentUnavailable")
                .contains("HighHttpServerErrorRatio")
                .contains("HighHttpRequestLatency")
                .contains("RagRetrievalFailures")
                .contains("KnowledgeIndexFailures")
                .contains("KnowledgeIndexRetryPressure");
        assertThat(alertmanager)
                .contains("smarthost: mailpit:1025")
                .contains("require_tls: false");
        assertThat(datasource).contains("url: http://prometheus:9090");
        assertThat(dashboard)
                .contains("multimodalagent_http_request_seconds")
                .contains("multimodalagent_rag_retrieval_seconds")
                .contains("multimodalagent_knowledge_index_seconds");
    }

    @Test
    void ciValidatesPrometheusAndAlertmanagerConfiguration() throws IOException {
        String workflow = readFile(".github/workflows/ci.yml");

        assertThat(workflow)
                .contains("Validate Prometheus configuration")
                .contains("--entrypoint=/bin/promtool")
                .contains("check config /etc/prometheus/prometheus.yml")
                .contains("Validate Alertmanager configuration")
                .contains("--entrypoint=/bin/amtool")
                .contains("check-config /etc/alertmanager/alertmanager.yml");
    }

    @Test
    void observabilityRunbookDefinesStartupVerificationAlertDrillAndProductionBoundaries() throws IOException {
        String runbook = readFile("docs/runbooks/observability.md");
        String adr = readFile("docs/adr/0022-prometheus-alertmanager-grafana-observability-stack.md");

        assertThat(runbook)
                .contains("docker compose --profile observability up --build -d")
                .contains("http://localhost:3000", "http://localhost:9090", "http://localhost:9093")
                .contains("http://localhost:8025")
                .contains("MultimodalAgentUnavailable")
                .containsIgnoringCase("production")
                .containsIgnoringCase("management port")
                .containsIgnoringCase("receiver")
                .containsIgnoringCase("password");
        assertThat(adr)
                .contains("Status: Accepted")
                .contains("Prometheus", "Alertmanager", "Grafana")
                .contains("bounded")
                .contains("Mailpit")
                .contains("management port");
    }

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
    void migrationSmokeScriptSupportsWindowsAndLinuxPowershellHosts() throws IOException {
        String script = readFile("scripts/mysql-migration-smoke.ps1");

        assertThat(script)
                .contains("$isWindowsHost", "$startProcessArguments")
                .contains("$startProcessArguments.WindowStyle = \"Hidden\"")
                .contains("Join-Path $repoRoot \"target\"")
                .contains("[string]$ComposeFile,", "[string]$JarPath,")
                .contains("$ComposeFile = Join-Path $repoRoot \"docker-compose.mysql-smoke.yml\"")
                .contains("$JarPath = Join-Path $targetDir \"multimodalAgent-agent-0.1.0.jar\"")
                .doesNotContain("[string]$ComposeFile = (Join-Path")
                .doesNotContain("..\\docker-compose.mysql-smoke.yml")
                .doesNotContain("..\\target\\multimodalAgent-agent-0.1.0.jar");
    }

    @Test
    void migrationSmokeScriptCapturesEarlyDiagnosticsAndCompletesCleanup() throws IOException {
        String script = readFile("scripts/mysql-migration-smoke.ps1");

        assertThat(script)
                .contains("mysql-migration-smoke-$PID.diagnostics.log")
                .contains("$projectName = \"multimodalagent-mysql-smoke-$PID\"")
                .contains("docker compose @composeArgs logs --no-color mysql")
                .contains("Set-Content -LiteralPath $diagnosticLog")
                .contains("$ErrorActionPreference = \"Continue\"")
                .contains("$scriptFailed", "$cleanupFailures")
                .contains("if (-not $scriptFailed -and $cleanupFailures.Count -gt 0)")
                .contains("Could not stop application process")
                .contains("Compose cleanup failed with exit code")
                .doesNotContain("multimodalAgent-mysql-smoke-$PID");
    }

    @Test
    void productionRunbookDefinesFreshAndExistingDatabasePathsAndRollback() throws IOException {
        String runbook = readFile("docs/runbooks/mysql-production-rollout.md");

        assertThat(runbook)
                .contains("V0", "V1", "V2", "V3")
                .containsIgnoringCase("baseline")
                .containsIgnoringCase("backup")
                .containsIgnoringCase("rollback")
                .contains("mysql-migration-smoke.ps1")
                .contains(".github/workflows/ci.yml", "Java tests", "MySQL migration smoke")
                .containsIgnoringCase("artifact");
    }

    @Test
    void ciWorkflowGatesChangesWithTestsAndMysqlMigrationSmoke() throws IOException {
        Map<String, Object> workflow = readYaml(".github/workflows/ci.yml");
        Map<String, Object> permissions = mapValue(workflow, "permissions");
        Map<String, Object> concurrency = mapValue(workflow, "concurrency");
        Map<String, Object> jobs = mapValue(workflow, "jobs");
        Map<String, Object> testJob = mapValue(jobs, "test");
        Map<String, Object> smokeJob = mapValue(jobs, "mysql-migration-smoke");

        assertThat(permissions).containsEntry("contents", "read");
        assertThat(concurrency).containsEntry("cancel-in-progress", true);
        assertThat(testJob)
                .containsEntry("runs-on", "ubuntu-latest")
                .containsKey("timeout-minutes");
        assertThat(smokeJob)
                .containsEntry("runs-on", "ubuntu-latest")
                .containsKey("timeout-minutes");

        List<Map<String, Object>> testSteps = stepsOf(testJob);
        assertThat(stepNamed(testSteps, "Check out repository"))
                .containsEntry("uses", "actions/checkout@v7");
        assertJava17Setup(stepNamed(testSteps, "Set up Java 17"));
        assertThat(stepNamed(testSteps, "Run test suite"))
                .containsEntry("run", "mvn --batch-mode --no-transfer-progress -DforkCount=0 test");
        assertFailureArtifact(stepNamed(testSteps, "Upload test reports on failure"));

        List<Map<String, Object>> smokeSteps = stepsOf(smokeJob);
        assertJava17Setup(stepNamed(smokeSteps, "Set up Java 17"));
        assertThat(stepNamed(smokeSteps, "Install MySQL client").get("run").toString())
                .contains("default-mysql-client");
        assertThat(stepNamed(smokeSteps, "Run Flyway V0 through V3 smoke"))
                .containsEntry("shell", "pwsh")
                .containsEntry("run", "./scripts/mysql-migration-smoke.ps1");
        assertFailureArtifact(stepNamed(smokeSteps, "Upload migration smoke logs on failure"));
    }

    private void assertJava17Setup(Map<String, Object> step) {
        assertThat(step).containsEntry("uses", "actions/setup-java@v5");
        assertThat(mapValue(step, "with"))
                .containsEntry("distribution", "temurin")
                .containsEntry("java-version", "17")
                .containsEntry("cache", "maven");
    }

    private void assertFailureArtifact(Map<String, Object> step) {
        assertThat(step)
                .containsEntry("if", "failure()")
                .containsEntry("uses", "actions/upload-artifact@v7");
        assertThat(mapValue(step, "with")).containsEntry("retention-days", 7);
    }

    private Map<String, Object> stepNamed(List<Map<String, Object>> steps, String name) {
        return steps.stream()
                .filter(step -> name.equals(step.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing workflow step: " + name));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> stepsOf(Map<String, Object> job) {
        return (List<Map<String, Object>>) job.get("steps");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYaml(String path) throws IOException {
        return (Map<String, Object>) new Yaml().load(readFile(path));
    }

    private String readFile(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
