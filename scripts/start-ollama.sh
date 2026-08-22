#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

. "$ROOT_DIR/scripts/load-dotenv.sh"
load_dotenv_file "$ROOT_DIR/.env"

DEFAULT_OLLAMA_BIN="$(command -v ollama || true)"
if [ -z "$DEFAULT_OLLAMA_BIN" ] && [ -x "/Applications/Ollama.app/Contents/Resources/ollama" ]; then
  DEFAULT_OLLAMA_BIN="/Applications/Ollama.app/Contents/Resources/ollama"
fi
OLLAMA_BIN="${OLLAMA_BIN:-$DEFAULT_OLLAMA_BIN}"
if [ -z "${OLLAMA_HOST:-}" ] || [ -z "${OLLAMA_BASE_URL:-}" ]; then
  echo "OLLAMA_HOST and OLLAMA_BASE_URL must be configured in .env or the environment."
  exit 1
fi

if [ ! -x "$OLLAMA_BIN" ]; then
  echo "Cannot find Ollama."
  echo "Install Ollama or set OLLAMA_BIN to the ollama executable path."
  exit 1
fi

if curl -fsS "$OLLAMA_BASE_URL/api/tags" >/dev/null 2>&1; then
  echo "Ollama is already running at $OLLAMA_BASE_URL"
  exit 0
fi

echo "Starting Ollama at $OLLAMA_HOST ..."
exec env OLLAMA_HOST="$OLLAMA_HOST" "$OLLAMA_BIN" serve
