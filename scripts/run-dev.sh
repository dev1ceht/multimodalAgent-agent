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
if [ -z "${OLLAMA_HOST:-}" ] || [ -z "${OLLAMA_BASE_URL:-}" ] || [ -z "${OLLAMA_MODEL:-}" ]; then
  echo "OLLAMA_HOST, OLLAMA_BASE_URL and OLLAMA_MODEL must be configured in .env or the environment."
  exit 1
fi
OLLAMA_HOST="$OLLAMA_HOST"
OLLAMA_BASE_URL="$OLLAMA_BASE_URL"
OLLAMA_MODEL="$OLLAMA_MODEL"
OLLAMA_BENCHMARK_MODEL="${OLLAMA_BENCHMARK_MODEL:-$OLLAMA_MODEL}"
if [ -z "${JAVA_HOME:-}" ] && [ -d "$ROOT_DIR/.tools/amazon-corretto-17.jdk/Contents/Home" ]; then
  export JAVA_HOME="$ROOT_DIR/.tools/amazon-corretto-17.jdk/Contents/Home"
fi
if [ -x "$ROOT_DIR/.tools/apache-maven-3.9.9/bin/mvn" ]; then
  DEFAULT_MAVEN_BIN="$ROOT_DIR/.tools/apache-maven-3.9.9/bin/mvn"
else
  DEFAULT_MAVEN_BIN="$(command -v mvn || true)"
fi
MAVEN_BIN="${MAVEN_BIN:-$DEFAULT_MAVEN_BIN}"

if [ ! -x "$OLLAMA_BIN" ]; then
  echo "Cannot find Ollama."
  echo "Install Ollama or set OLLAMA_BIN to the ollama executable path."
  exit 1
fi

if [ ! -x "$MAVEN_BIN" ]; then
  echo "Cannot find Maven."
  echo "Install Maven or set MAVEN_BIN to the mvn executable path."
  exit 1
fi

mkdir -p data

if ! curl -fsS "$OLLAMA_BASE_URL/api/tags" >/dev/null 2>&1; then
  echo "Starting Ollama on $OLLAMA_HOST ..."
  OLLAMA_HOST="$OLLAMA_HOST" "$OLLAMA_BIN" serve > data/ollama.log 2>&1 &

  for _ in $(seq 1 30); do
    if curl -fsS "$OLLAMA_BASE_URL/api/tags" >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
fi

if ! curl -fsS "$OLLAMA_BASE_URL/api/tags" >/dev/null 2>&1; then
  echo "Ollama did not start. Check data/ollama.log."
  exit 1
fi

if ! "$OLLAMA_BIN" list | awk 'NR > 1 {print $1}' | grep -qx "$OLLAMA_MODEL"; then
  if [ "$OLLAMA_MODEL" = "$OLLAMA_BENCHMARK_MODEL" ] && [ -f "$ROOT_DIR/models/Modelfile.qwen35-benchmark" ]; then
    echo "Creating $OLLAMA_MODEL from models/Modelfile.qwen35-benchmark ..."
    "$OLLAMA_BIN" create "$OLLAMA_MODEL" -f "$ROOT_DIR/models/Modelfile.qwen35-benchmark"
  else
    echo "Pulling $OLLAMA_MODEL ..."
    "$OLLAMA_BIN" pull "$OLLAMA_MODEL"
  fi
fi

AI_PROVIDER=ollama \
DEMO_ACCOUNTS_ENABLED="${DEMO_ACCOUNTS_ENABLED:-true}" \
OLLAMA_BASE_URL="$OLLAMA_BASE_URL" \
OLLAMA_MODEL="$OLLAMA_MODEL" \
  "$MAVEN_BIN" -Dmaven.repo.local=.m2/repository spring-boot:run
