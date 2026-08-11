param(
    [Parameter(Mandatory = $true)]
    [string]$RunId,

    [ValidatePattern("^[A-Za-z0-9_-]+$")]
    [string]$Label = "current",

    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$runRoot = Join-Path $projectRoot "benchmarks\results\$RunId"
$traceDir = Join-Path $runRoot "traces\$Label"
New-Item -ItemType Directory -Force -Path $traceDir | Out-Null

# Only enable evaluation tracing. Model, retrieval, embedding, database, and
# generation settings come from the application's current configuration.
$env:SERVER_PORT = "$Port"
$env:EVAL_MODE = "true"
$env:EVAL_OUTPUT_DIR = $traceDir

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
