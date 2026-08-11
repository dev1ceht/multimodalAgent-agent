param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("qwen25", "qwen35")]
    [string]$Model,

    [Parameter(Mandatory = $true)]
    [string]$RunId,

    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$modelTags = @{
    qwen25 = "multimodalAgent-qwen2.5-7b-benchmark:latest"
    qwen35 = "multimodalAgent-qwen3.5-9b-benchmark:latest"
}

$runRoot = Join-Path $projectRoot "benchmarks\results\$RunId"
$traceDir = Join-Path $runRoot "traces\$Model"
$dbDir = Join-Path $runRoot "db"
$toolDir = Join-Path $runRoot "tools"
$safeRunId = ($RunId -replace '[^A-Za-z0-9_-]', '_')
$elasticsearchPrefix = "multimodalagent-eval-${safeRunId}-${Model}"
New-Item -ItemType Directory -Force -Path $traceDir, $dbDir, $toolDir | Out-Null

$env:SERVER_PORT = "$Port"
$env:AI_PROVIDER = "ollama"
$env:OLLAMA_BASE_URL = "http://127.0.0.1:11434"
$env:OLLAMA_MODEL = $modelTags[$Model]
$env:AI_TEMPERATURE = "0.35"
$env:AI_MAX_TOKENS = "512"
$env:AI_CONTEXT_WINDOW = "4096"
$env:USE_ELASTICSEARCH = "true"
$env:RAG_RETRIEVAL_MODE = "ELASTICSEARCH_REQUIRED"
$env:RAG_TOP_K = "4"
$env:ELASTICSEARCH_BASE_URL = "http://127.0.0.1:9200"
$env:ELASTICSEARCH_INDEX_PREFIX = $elasticsearchPrefix
$env:ELASTICSEARCH_ACTIVE_ALIAS = "${elasticsearchPrefix}-active"
$env:EVAL_MODE = "true"
$env:EVAL_OUTPUT_DIR = $traceDir
$env:DB_URL = "jdbc:h2:file:$($dbDir.Replace('\','/'))/$Model;MODE=MySQL;DATABASE_TO_LOWER=TRUE"
$env:MCP_EXCEL_MODE = "local"
$env:MCP_EXCEL_LOCAL_PATH = Join-Path $toolDir "$Model-reports.xlsx"
$env:MCP_EMAIL_MODE = "log"
$env:DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode"
$env:EMBEDDING_MODEL = "text-embedding-v4"
$env:EMBEDDING_DIMENSIONS = "1024"

Push-Location $projectRoot
try {
    & mvn.cmd spring-boot:run
    if ($LASTEXITCODE -ne 0) {
        throw "Benchmark application exited with code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}
