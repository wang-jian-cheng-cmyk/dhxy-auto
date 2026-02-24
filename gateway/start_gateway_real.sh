#!/usr/bin/env bash
set -euo pipefail

PORT="${PORT:-8787}"
HOST="${HOST:-127.0.0.1}"
LOG_FILE="${LOG_FILE:-$HOME/dhxy_gateway_real.log}"

CONDA_ROOT="/root/miniconda3"
if [ -x "$HOME/miniconda3/bin/conda" ]; then
  CONDA_ROOT="$HOME/miniconda3"
fi

if [ ! -x "$CONDA_ROOT/bin/conda" ]; then
  echo "Conda not found at $CONDA_ROOT/bin/conda"
  exit 1
fi

cd "$(dirname "$0")"

pkill -f "uvicorn main:app --host $HOST --port $PORT" || true

"$CONDA_ROOT/bin/conda" run -n LLM uvicorn main:app --host "$HOST" --port "$PORT" >>"$LOG_FILE" 2>&1
