[CmdletBinding()]
param(
    [int]$DockerWaitTimeoutSeconds = 180,
    [int]$OllamaWaitTimeoutSeconds = 60
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot

function Import-DotEnv {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }

    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*(?<name>[A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?<value>.*)\s*$') {
            $name = $Matches['name']
            if (-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
                continue
            }
            $value = $Matches['value'].Trim()
            if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                    ($value.StartsWith("'") -and $value.EndsWith("'"))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
}

function Set-EnvironmentDefault {
    param(
        [string]$Name,
        [string]$Value
    )

    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($Name, 'Process'))) {
        [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
    }
}

Import-DotEnv (Join-Path $projectRoot ".env")

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker CLI was not found. Install or start Docker Desktop first."
}

$mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $mavenCommand) {
    $mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue
}
if (-not $mavenCommand) {
    throw "Maven was not found. Install Maven and add it to PATH first."
}

function Get-OllamaTags {
    try {
        return Invoke-RestMethod `
            -Uri "$env:OLLAMA_BASE_URL/api/tags" `
            -Method Get `
            -TimeoutSec 2
    }
    catch {
        return $null
    }
}

function Resolve-OllamaExecutable {
    $command = Get-Command ollama.exe -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $candidates = @(
        (Join-Path $env:LOCALAPPDATA "Programs\Ollama\ollama.exe"),
        (Join-Path $env:LOCALAPPDATA "Ollama\ollama.exe"),
        (Join-Path $env:ProgramFiles "Ollama\ollama.exe")
    )
    return $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}

if ([string]::IsNullOrWhiteSpace($env:JWT_SECRET)) {
    $env:JWT_SECRET = [Guid]::NewGuid().ToString("N") + [Guid]::NewGuid().ToString("N")
}

Set-EnvironmentDefault "SPRING_PROFILES_ACTIVE" "mysql"
Set-EnvironmentDefault "SERVER_PORT" "8080"
Set-EnvironmentDefault "AI_PROVIDER" "ollama"
Set-EnvironmentDefault "OLLAMA_BENCHMARK_MODEL" $env:OLLAMA_MODEL
if ([string]::IsNullOrWhiteSpace($env:OLLAMA_BASE_URL) -or
        [string]::IsNullOrWhiteSpace($env:OLLAMA_MODEL)) {
    throw "OLLAMA_BASE_URL and OLLAMA_MODEL must be configured in .env or the environment."
}
$env:REFRESH_COOKIE_SECURE = "false"
$env:DEMO_ACCOUNTS_ENABLED = "true"
$env:AUTH_SESSION_STORE = "redis"
Set-EnvironmentDefault "REDIS_HOST" "127.0.0.1"
$env:MCP_EXCEL_MODE = "local"
$env:MCP_EMAIL_MODE = "log"

Set-EnvironmentDefault "DB_URL" $env:DEV_DB_URL
Set-EnvironmentDefault "DB_USERNAME" $env:MYSQL_USER
Set-EnvironmentDefault "DB_PASSWORD" $env:MYSQL_PASSWORD
if ([string]::IsNullOrWhiteSpace($env:DB_URL) -or
        [string]::IsNullOrWhiteSpace($env:DB_USERNAME) -or
        [string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
    throw "DEV_DB_URL, MYSQL_USER and MYSQL_PASSWORD must be configured in .env or the environment."
}
if ([string]::IsNullOrWhiteSpace($env:AUDIT_RESOURCE_HASH_SECRET)) {
    $env:AUDIT_RESOURCE_HASH_SECRET = [Guid]::NewGuid().ToString("N")
}

$ollamaTags = Get-OllamaTags
if (-not $ollamaTags) {
    $ollamaExecutable = Resolve-OllamaExecutable
    if (-not $ollamaExecutable) {
        throw "Ollama was not found. Install Ollama or add ollama.exe to PATH first."
    }

    Write-Host "Starting local Ollama service..."
    $startProcessArguments = @{
        FilePath = $ollamaExecutable
        ArgumentList = @("serve")
        WindowStyle = "Hidden"
    }
    Start-Process @startProcessArguments

    $ollamaDeadline = (Get-Date).AddSeconds($OllamaWaitTimeoutSeconds)
    do {
        Start-Sleep -Milliseconds 500
        $ollamaTags = Get-OllamaTags
    } while (-not $ollamaTags -and (Get-Date) -lt $ollamaDeadline)

    if (-not $ollamaTags) {
        throw "Ollama did not become ready at $env:OLLAMA_BASE_URL within $OllamaWaitTimeoutSeconds seconds."
    }
}

$installedOllamaModels = @($ollamaTags.models | ForEach-Object { $_.name })
if ($installedOllamaModels -notcontains $env:OLLAMA_MODEL) {
    throw "Ollama model '$env:OLLAMA_MODEL' is not installed. Installed models: $($installedOllamaModels -join ', ')."
}
Write-Host "Ollama is ready with model $env:OLLAMA_MODEL."

Push-Location $projectRoot
try {
    Write-Host "Starting Docker development dependencies..."
    & docker compose up -d --wait --wait-timeout $DockerWaitTimeoutSeconds `
        mysql redis elasticsearch mailpit
    if ($LASTEXITCODE -ne 0) {
        throw "Docker development dependencies failed to become ready."
    }

    Write-Host "Starting Spring Boot with Docker MySQL and Redis..."
    & $mavenCommand.Source spring-boot:run
    if ($LASTEXITCODE -ne 0) {
        throw "Spring Boot exited with code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}
