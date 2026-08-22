#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
. "$ROOT_DIR/scripts/load-dotenv.sh"
load_dotenv_file "$ROOT_DIR/.env"

DEFAULT_OLLAMA_BIN="$(command -v ollama || true)"
if [ -z "$DEFAULT_OLLAMA_BIN" ] && [ -x "/Applications/Ollama.app/Contents/Resources/ollama" ]; then
  DEFAULT_OLLAMA_BIN="/Applications/Ollama.app/Contents/Resources/ollama"
fi
OLLAMA_BIN="${OLLAMA_BIN:-$DEFAULT_OLLAMA_BIN}"
if [ -z "${OLLAMA_BENCHMARK_MODEL:-}" ]; then
  echo "OLLAMA_BENCHMARK_MODEL must be configured in .env or the environment."
  exit 1
fi

if [ ! -x "$OLLAMA_BIN" ]; then
  echo "Cannot find Ollama."
  echo "Install Ollama or set OLLAMA_BIN to the ollama executable path."
  exit 1
fi

"$OLLAMA_BIN" create "$OLLAMA_BENCHMARK_MODEL" -f "$ROOT_DIR/models/Modelfile.qwen35-benchmark"

echo "Created $OLLAMA_BENCHMARK_MODEL"
echo "Run multimodalAgent with: ./scripts/run-dev.sh"
