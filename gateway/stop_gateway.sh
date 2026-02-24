#!/usr/bin/env bash
set -euo pipefail

PORT="${PORT:-8787}"
HOST="${HOST:-127.0.0.1}"

PIDS=$(pgrep -f "uvicorn main:app --host $HOST --port $PORT" || true)

if [ -z "$PIDS" ]; then
  echo "No gateway process found on $HOST:$PORT"
  exit 0
fi

echo "Stopping gateway PID(s): $PIDS"
kill $PIDS

sleep 1
LEFT=$(pgrep -f "uvicorn main:app --host $HOST --port $PORT" || true)
if [ -n "$LEFT" ]; then
  echo "Force stopping leftover PID(s): $LEFT"
  kill -9 $LEFT
fi

echo "Gateway stopped"
