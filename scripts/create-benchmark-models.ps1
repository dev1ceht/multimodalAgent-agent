param(
    [string]$OllamaBin = "$env:LOCALAPPDATA\Programs\Ollama\ollama.exe"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$modelsDir = Join-Path $projectRoot "models"
$envFile = Join-Path $projectRoot ".env"
if (Test-Path -LiteralPath $envFile) {
    foreach ($line in Get-Content -LiteralPath $envFile) {
        if ($line -match '^\s*(?<name>[A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?<value>.*)\s*$') {
            $name = $Matches['name']
            if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
                $value = $Matches['value'].Trim()
                if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                        ($value.StartsWith("'") -and $value.EndsWith("'"))) {
                    $value = $value.Substring(1, $value.Length - 2)
                }
                [Environment]::SetEnvironmentVariable($name, $value, 'Process')
            }
        }
    }
}
$qwen25Model = $env:OLLAMA_QWEN25_BENCHMARK_MODEL
$qwen35Model = $env:OLLAMA_QWEN35_BENCHMARK_MODEL
if ([string]::IsNullOrWhiteSpace($qwen25Model) -or [string]::IsNullOrWhiteSpace($qwen35Model)) {
    throw "OLLAMA_QWEN25_BENCHMARK_MODEL and OLLAMA_QWEN35_BENCHMARK_MODEL must be configured in .env or the environment."
}

if (-not (Test-Path -LiteralPath $OllamaBin)) {
    throw "Cannot find Ollama at '$OllamaBin'. Pass -OllamaBin with the executable path."
}

$definitions = @(
    @{
        Name = $qwen25Model
        File = Join-Path $modelsDir "Modelfile.qwen25-benchmark"
    },
    @{
        Name = $qwen35Model
        File = Join-Path $modelsDir "Modelfile.qwen35-benchmark"
    }
)

foreach ($definition in $definitions) {
    if (-not (Test-Path -LiteralPath $definition.File)) {
        throw "Missing Modelfile '$($definition.File)'."
    }
    & $OllamaBin create $definition.Name -f $definition.File
    if ($LASTEXITCODE -ne 0) {
        throw "Ollama failed to create '$($definition.Name)'."
    }
}

& $OllamaBin list
