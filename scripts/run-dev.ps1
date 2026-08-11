[CmdletBinding()]
param(
    [int]$DockerWaitTimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot

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

if ([string]::IsNullOrWhiteSpace($env:JWT_SECRET)) {
    $env:JWT_SECRET = "local-dev-jwt-secret-change-me-at-least-32-bytes"
}

$env:SPRING_PROFILES_ACTIVE = "mysql"
$env:SERVER_PORT = "8080"
$env:AI_PROVIDER = "ollama"
$env:OLLAMA_BASE_URL = "http://127.0.0.1:11434"
$env:OLLAMA_MODEL = "multimodalAgent-qwen3.5-9b-benchmark:latest"
$env:REFRESH_COOKIE_SECURE = "false"
$env:DEMO_ACCOUNTS_ENABLED = "true"
$env:AUTH_SESSION_STORE = "redis"
$env:REDIS_HOST = "127.0.0.1"
$env:MCP_EXCEL_MODE = "local"
$env:MCP_EMAIL_MODE = "log"

if ([string]::IsNullOrWhiteSpace($env:DASHSCOPE_API_KEY)) {
    $env:USE_ELASTICSEARCH = "false"
    $env:RAG_RETRIEVAL_MODE = "LOCAL_BASELINE"
    Write-Host "DASHSCOPE_API_KEY is not set; using the local RAG baseline."
}
else {
    $env:USE_ELASTICSEARCH = "true"
    $env:RAG_RETRIEVAL_MODE = "ELASTICSEARCH_REQUIRED"
    $env:ELASTICSEARCH_BASE_URL = "http://127.0.0.1:9200"
    Write-Host "DASHSCOPE_API_KEY is set; using Elasticsearch hybrid retrieval."
}

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
