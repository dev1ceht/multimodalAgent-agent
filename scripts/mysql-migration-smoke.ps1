[CmdletBinding()]
param(
    [string]$ComposeFile = (Join-Path $PSScriptRoot "..\docker-compose.mysql-smoke.yml"),
    [string]$JarPath = (Join-Path $PSScriptRoot "..\target\multimodalAgent-agent-0.1.0.jar"),
    [int]$HostPort = 33306,
    [int]$AppPort = 18081,
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$projectName = "multimodalAgent-mysql-smoke-$PID"
$composeArgs = @("-p", $projectName, "-f", $ComposeFile)
$appProcess = $null
$appLog = Join-Path $repoRoot "target\mysql-migration-smoke-$PID.log"
$appErrorLog = Join-Path $repoRoot "target\mysql-migration-smoke-$PID.err.log"
$environmentOverrides = @{
    SPRING_PROFILES_ACTIVE = "mysql"
    SERVER_PORT = "$AppPort"
    DB_URL = "jdbc:mysql://127.0.0.1:$HostPort/multimodalAgent?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    DB_USERNAME = "multimodalAgent"
    DB_PASSWORD = "multimodalAgent"
    AI_PROVIDER = "mock"
    USE_CHROMA = "false"
    RAG_RETRIEVAL_MODE = "LOCAL_BASELINE"
    RAG_INDEX_SYNC_ENABLED = "false"
    MCP_EXCEL_MODE = "local"
    MCP_EMAIL_MODE = "log"
    REDIS_HOST = "127.0.0.1"
    MANAGEMENT_HEALTH_REDIS_ENABLED = "false"
}
$oldEnvironment = @{}
$oldMysqlPassword = [Environment]::GetEnvironmentVariable("MYSQL_PWD", "Process")

try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw "docker command is required"
    }
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        throw "java command is required"
    }
    if (-not (Get-Command mysql -ErrorAction SilentlyContinue)) {
        throw "mysql client command is required"
    }
    if (-not (Test-Path -LiteralPath $JarPath)) {
        Push-Location $repoRoot
        try {
            & mvn -q -DskipTests package
            if ($LASTEXITCODE -ne 0) {
                throw "Maven package failed"
            }
        } finally {
            Pop-Location
        }
    }

    & docker compose @composeArgs up -d
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL smoke compose failed to start"
    }

    $containerId = (& docker compose @composeArgs ps -q mysql).Trim()
    if ([string]::IsNullOrWhiteSpace($containerId)) {
        throw "MySQL smoke container id was not returned"
    }
    $healthDeadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $health = (& docker inspect --format '{{.State.Health.Status}}' $containerId 2>$null).Trim()
        if ($health -eq "healthy") {
            break
        }
        if ($health -eq "unhealthy") {
            throw "MySQL smoke container became unhealthy"
        }
        if ((Get-Date) -gt $healthDeadline) {
            throw "Timed out waiting for MySQL smoke container health"
        }
        Start-Sleep -Seconds 2
    } while ($true)

    foreach ($entry in $environmentOverrides.GetEnumerator()) {
        $oldEnvironment[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, "Process")
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
    }

    $appProcess = Start-Process -FilePath "java" `
        -ArgumentList @("-jar", $JarPath) `
        -WorkingDirectory $repoRoot `
        -RedirectStandardOutput $appLog `
        -RedirectStandardError $appErrorLog `
        -WindowStyle Hidden `
        -PassThru

    $appDeadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if ($appProcess.HasExited) {
            throw "Application exited before becoming healthy; inspect $appLog"
        }
        try {
            $healthResponse = Invoke-WebRequest `
                -UseBasicParsing `
                -Uri "http://127.0.0.1:$AppPort/actuator/health" `
                -TimeoutSec 5
            if ($healthResponse.StatusCode -eq 200) {
                break
            }
        } catch {
            # The application is still starting or the port is not open yet.
        }
        if ((Get-Date) -gt $appDeadline) {
            throw "Timed out waiting for application health; inspect $appLog"
        }
        Start-Sleep -Seconds 2
    } while ($true)

    [Environment]::SetEnvironmentVariable("MYSQL_PWD", "multimodalAgent", "Process")
    $history = & mysql --protocol=TCP --host=127.0.0.1 --port=$HostPort `
        --user=multimodalAgent --database=multimodalAgent --batch --skip-column-names `
        -e "SELECT version FROM flyway_schema_history ORDER BY installed_rank;"
    if ($LASTEXITCODE -ne 0) {
        throw "Could not read flyway_schema_history"
    }
    $versions = @($history | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    # Verifies Flyway V0, V1, V2, V3 in order on a fresh database.
    $expectedVersions = @("0", "1", "2", "3")
    if (($versions -join ",") -ne ($expectedVersions -join ",")) {
        throw "Unexpected Flyway history: $($versions -join ', ')"
    }

    $columns = & mysql --protocol=TCP --host=127.0.0.1 --port=$HostPort `
        --user=multimodalAgent --database=multimodalAgent --batch --skip-column-names `
        -e "SELECT CONCAT(table_name, '.', column_name) FROM information_schema.columns WHERE table_schema = DATABASE() AND ((table_name = 'risk_cases' AND column_name IN ('overdue_escalated_at', 'version', 'sla_due_at')) OR (table_name = 'delivery_tasks' AND column_name = 'risk_case_id')) ORDER BY table_name, column_name;"
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect migrated schema"
    }
    $requiredColumns = @(
        "delivery_tasks.risk_case_id",
        "risk_cases.overdue_escalated_at",
        "risk_cases.sla_due_at",
        "risk_cases.version"
    )
    foreach ($column in $requiredColumns) {
        if (-not ($columns -contains $column)) {
            throw "Required migrated column is missing: $column"
        }
    }

    Write-Host "MySQL migration smoke passed: Flyway V0 through V3 and ddl-auto=validate startup succeeded."
} finally {
    if ($appProcess -and -not $appProcess.HasExited) {
        Stop-Process -Id $appProcess.Id -Force
    }
    foreach ($entry in $oldEnvironment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
    }
    [Environment]::SetEnvironmentVariable("MYSQL_PWD", $oldMysqlPassword, "Process")
    try {
        & docker compose @composeArgs down -v --remove-orphans
    } catch {
        Write-Warning "Could not fully clean the smoke compose project: $($_.Exception.Message)"
    }
}
