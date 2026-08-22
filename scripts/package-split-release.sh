#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_NAME="multimodalAgent"
. "$ROOT_DIR/scripts/load-dotenv.sh"
load_dotenv_file "$ROOT_DIR/.env"

MODEL_DIR_NAME="${MODEL_DIR_NAME:?Set MODEL_DIR_NAME in .env or the environment}"
MODEL_FILE_NAME="${MODEL_FILE_NAME:?Set MODEL_FILE_NAME in .env or the environment}"
MODELFILE_NAME="${MODELFILE_NAME:?Set MODELFILE_NAME in .env or the environment}"
DATASET_FILE="$ROOT_DIR/data/lora/psychqa.jsonl"
STAMP="$(date +%Y%m%d-%H%M%S)"
DIST_DIR="$ROOT_DIR/dist"
STAGE_DIR="$DIST_DIR/split-stage-$STAMP"
APP_ARCHIVE="$DIST_DIR/${PROJECT_NAME}-app-$STAMP.tar.gz"
MODEL_ARCHIVE="$DIST_DIR/${PROJECT_NAME}-model-$STAMP.tar.gz"

MODEL_DIR="${MODEL_DIR:-$ROOT_DIR/dist/multimodalAgent-langding/models/$MODEL_DIR_NAME}"
if [ ! -f "$MODEL_DIR/$MODEL_FILE_NAME" ]; then
  MODEL_DIR="$ROOT_DIR/models"
fi

if [ ! -f "$MODEL_DIR/$MODEL_FILE_NAME" ]; then
  echo "Missing model file: $MODEL_DIR/$MODEL_FILE_NAME"
  echo "Set MODEL_DIR to the directory containing $MODEL_FILE_NAME."
  exit 1
fi

if [ ! -f "$DATASET_FILE" ]; then
  echo "Missing dataset file: $DATASET_FILE"
  exit 1
fi

mkdir -p "$DIST_DIR"
rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR/$PROJECT_NAME" "$STAGE_DIR/models"

rsync -a "$ROOT_DIR/" "$STAGE_DIR/$PROJECT_NAME/" \
  --exclude '.git/' \
  --exclude '.env' \
  --exclude '.idea/' \
  --exclude '.vscode/' \
  --exclude '.m2/' \
  --exclude '.tools/' \
  --exclude 'target/' \
  --exclude 'dist/' \
  --exclude 'models/' \
  --include 'data/' \
  --include 'data/lora/' \
  --include 'data/lora/psychqa.jsonl' \
  --exclude 'data/**' \
  --exclude 'logs/' \
  --exclude 'scripts/generate-lora-dataset.py' \
  --exclude '*.pdf' \
  --exclude '.DS_Store' \
  --exclude '*.iml' \
  --exclude '*.log' \
  --exclude 'run.log' \
  --exclude 'data/*.db' \
  --exclude 'data/*.mv.db' \
  --exclude 'data/*.trace.db' \
  --exclude 'data/*.xlsx'

rsync -a "$MODEL_DIR/$MODEL_FILE_NAME" "$STAGE_DIR/models/$MODEL_DIR_NAME/"
if [ -f "$MODEL_DIR/$MODELFILE_NAME" ]; then
  rsync -a "$MODEL_DIR/$MODELFILE_NAME" "$STAGE_DIR/models/$MODEL_DIR_NAME/"
fi

(
  cd "$STAGE_DIR"
  COPYFILE_DISABLE=1 tar -czf "$APP_ARCHIVE" "$PROJECT_NAME"
  COPYFILE_DISABLE=1 tar -czf "$MODEL_ARCHIVE" models
)

rm -rf "$STAGE_DIR"

echo "Created split release packages:"
echo "$APP_ARCHIVE"
du -sh "$APP_ARCHIVE"
echo "$MODEL_ARCHIVE"
du -sh "$MODEL_ARCHIVE"
