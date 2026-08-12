[CmdletBinding()]
param(
    [string]$ComposeFile,
    [string]$JarPath,
    [int]$HostPort = 33306,
    [int]$AppPort = 18081,
    [int]$ManagementPort = 19090,
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$targetDir = Join-Path $repoRoot "target"
if ([string]::IsNullOrWhiteSpace($ComposeFile)) {
    $ComposeFile = Join-Path $repoRoot "docker-compose.mysql-smoke.yml"
}
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $JarPath = Join-Path $targetDir "multimodalAgent-agent-0.1.0.jar"
}
$isWindowsHost = $PSVersionTable.PSEdition -eq "Desktop" -or $IsWindows
$projectName = "multimodalagent-mysql-smoke-$PID"
$composeArgs = @("-p", $projectName, "-f", $ComposeFile)
$appProcess = $null
$appLog = Join-Path $targetDir "mysql-migration-smoke-$PID.log"
$appErrorLog = Join-Path $targetDir "mysql-migration-smoke-$PID.err.log"
$diagnosticLog = Join-Path $targetDir "mysql-migration-smoke-$PID.diagnostics.log"
$scriptFailed = $false
$environmentOverrides = @{
    SPRING_PROFILES_ACTIVE = "mysql"
    SERVER_PORT = "$AppPort"
    MANAGEMENT_SERVER_PORT = "$ManagementPort"
    DB_URL = "jdbc:mysql://127.0.0.1:$HostPort/multimodalAgent?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    DB_USERNAME = "multimodalAgent"
    DB_PASSWORD = "multimodalAgent"
    JWT_SECRET = "mysql-smoke-only-jwt-secret-32-bytes"
    AI_PROVIDER = "mock"
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
    New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
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

    $startProcessArguments = @{
        FilePath = "java"
        ArgumentList = @("-jar", $JarPath)
        WorkingDirectory = $repoRoot
        RedirectStandardOutput = $appLog
        RedirectStandardError = $appErrorLog
        PassThru = $true
    }
    if ($isWindowsHost) {
        $startProcessArguments.WindowStyle = "Hidden"
    }
    $appProcess = Start-Process @startProcessArguments

    $appDeadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if ($appProcess.HasExited) {
            throw "Application exited before becoming healthy; inspect $appLog"
        }
        try {
            $healthResponse = Invoke-WebRequest `
                -UseBasicParsing `
                -Uri "http://127.0.0.1:$ManagementPort/actuator/health" `
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
    # Verifies Flyway V0, V1, V2, V3, V4 in order on a fresh database.
    $expectedVersions = @("0", "1", "2", "3", "4")
    if (($versions -join ",") -ne ($expectedVersions -join ",")) {
        throw "Unexpected Flyway history: $($versions -join ', ')"
    }

    $columns = & mysql --protocol=TCP --host=127.0.0.1 --port=$HostPort `
        --user=multimodalAgent --database=multimodalAgent --batch --skip-column-names `
        -e "SELECT CONCAT(table_name, '.', column_name) FROM information_schema.columns WHERE table_schema = DATABASE() AND ((table_name = 'knowledge_documents' AND column_name = 'version') OR (table_name = 'risk_cases' AND column_name IN ('overdue_escalated_at', 'version', 'sla_due_at')) OR (table_name = 'delivery_tasks' AND column_name = 'risk_case_id')) ORDER BY table_name, column_name;"
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect migrated schema"
    }
    $requiredColumns = @(
        "delivery_tasks.risk_case_id",
        "knowledge_documents.version",
        "risk_cases.overdue_escalated_at",
        "risk_cases.sla_due_at",
        "risk_cases.version"
    )
    foreach ($column in $requiredColumns) {
        if (-not ($columns -contains $column)) {
            throw "Required migrated column is missing: $column"
        }
    }

    Write-Host "MySQL migration smoke passed: Flyway V0 through V4 and ddl-auto=validate startup succeeded."
} catch {
    $scriptFailed = $true
    $failureRecord = $_
    $diagnosticLines = @(
        "timestamp=$([DateTimeOffset]::UtcNow.ToString('O'))",
        "failure=$($failureRecord.Exception.Message)",
        "composeProject=$projectName"
    )
    if (Get-Command docker -ErrorAction SilentlyContinue) {
        $diagnosticErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            try {
                $diagnosticLines += "=== docker compose ps ==="
                $diagnosticLines += @(
                    & docker compose @composeArgs ps --all 2>&1 | ForEach-Object { "$_" }
                )
            } catch {
                $diagnosticLines += "Could not collect compose status: $($_.Exception.Message)"
            }
            try {
                $diagnosticLines += "=== mysql container logs ==="
                $diagnosticLines += @(
                    & docker compose @composeArgs logs --no-color mysql 2>&1 | ForEach-Object { "$_" }
                )
            } catch {
                $diagnosticLines += "Could not collect MySQL logs: $($_.Exception.Message)"
            }
        } finally {
            $ErrorActionPreference = $diagnosticErrorActionPreference
        }
    }
    try {
        $diagnosticLines | Set-Content -LiteralPath $diagnosticLog -Encoding UTF8
    } catch {
        Write-Warning "Could not write smoke diagnostics: $($_.Exception.Message)"
    }
    throw $failureRecord
} finally {
    $cleanupFailures = @()
    if ($appProcess -and -not $appProcess.HasExited) {
        try {
            Stop-Process -Id $appProcess.Id -Force -ErrorAction Stop
        } catch {
            $message = "Could not stop application process: $($_.Exception.Message)"
            $cleanupFailures += $message
            Write-Warning $message
        }
    }
    foreach ($entry in $oldEnvironment.GetEnumerator()) {
        try {
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
        } catch {
            $message = "Could not restore process environment variable $($entry.Key): $($_.Exception.Message)"
            $cleanupFailures += $message
            Write-Warning $message
        }
    }
    try {
        [Environment]::SetEnvironmentVariable("MYSQL_PWD", $oldMysqlPassword, "Process")
    } catch {
        $message = "Could not restore MYSQL_PWD: $($_.Exception.Message)"
        $cleanupFailures += $message
        Write-Warning $message
    }
    try {
        & docker compose @composeArgs down -v --remove-orphans
        if ($LASTEXITCODE -ne 0) {
            $message = "Compose cleanup failed with exit code $LASTEXITCODE."
            $cleanupFailures += $message
            Write-Warning $message
        }
    } catch {
        $message = "Could not fully clean the smoke compose project: $($_.Exception.Message)"
        $cleanupFailures += $message
        Write-Warning $message
    }
    if (-not $scriptFailed -and $cleanupFailures.Count -gt 0) {
        throw "Smoke cleanup failed: $($cleanupFailures -join ' | ')"
    }
}
