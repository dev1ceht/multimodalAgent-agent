param(
    [string]$OllamaBin = "$env:LOCALAPPDATA\Programs\Ollama\ollama.exe"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$modelsDir = Join-Path $projectRoot "models"

if (-not (Test-Path -LiteralPath $OllamaBin)) {
    throw "Cannot find Ollama at '$OllamaBin'. Pass -OllamaBin with the executable path."
}

$definitions = @(
    @{
        Name = "multimodalAgent-qwen2.5-7b-benchmark:latest"
        File = Join-Path $modelsDir "Modelfile.qwen25-benchmark"
    },
    @{
        Name = "multimodalAgent-qwen3.5-9b-benchmark:latest"
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
