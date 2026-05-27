#!/usr/bin/env bash
# SWIFT Platform — Stop Script
set -euo pipefail

PID_DIR="${PID_DIR:-/opt/swift-platform/run}"

if [ -f "$PID_DIR/backend.pid" ]; then
    PID=$(cat "$PID_DIR/backend.pid")
    if kill -0 "$PID" 2>/dev/null; then
        echo "[SWIFT] Stopping backend (PID $PID)..."
        kill "$PID"
        rm -f "$PID_DIR/backend.pid"
        echo "[SWIFT] Backend stopped."
    else
        echo "[SWIFT] Backend not running (stale PID $PID)."
        rm -f "$PID_DIR/backend.pid"
    fi
else
    echo "[SWIFT] No backend PID file found."
fi

echo "[SWIFT] All services stopped."
