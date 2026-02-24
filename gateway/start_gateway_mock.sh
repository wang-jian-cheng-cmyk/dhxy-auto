#!/usr/bin/env bash
set -euo pipefail

PORT="${PORT:-8787}"
HOST="${HOST:-127.0.0.1}"
LOG_FILE="${LOG_FILE:-$HOME/dhxy_gateway_mock.log}"

CONDA_ROOT="/root/miniconda3"
if [ -x "$HOME/miniconda3/bin/conda" ]; then
  CONDA_ROOT="$HOME/miniconda3"
fi

if [ ! -x "$CONDA_ROOT/bin/conda" ]; then
  echo "Conda not found at $CONDA_ROOT/bin/conda"
  exit 1
fi

cd "$(dirname "$0")"

PROXY_ENV_FILE="${PROXY_ENV_FILE:-./proxy.env}"
if [ -f "$PROXY_ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$PROXY_ENV_FILE"
  set +a
fi

pkill -f "uvicorn main:app --host $HOST --port $PORT" || true

if [ -x "$CONDA_ROOT/envs/LLM/bin/uvicorn" ]; then
  MOCK_DECISION=1 PYTHONUNBUFFERED=1 "$CONDA_ROOT/envs/LLM/bin/uvicorn" main:app --host "$HOST" --port "$PORT" --log-level info --access-log >>"$LOG_FILE" 2>&1
else
  MOCK_DECISION=1 PYTHONUNBUFFERED=1 "$CONDA_ROOT/bin/conda" run -n LLM uvicorn main:app --host "$HOST" --port "$PORT" --log-level info --access-log >>"$LOG_FILE" 2>&1
fi
