#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════════════════
#  SWIFT Platform — Start Script
#  All paths and config read from environment variables.
#  Set them in /etc/environment or source a .env file before running.
# ══════════════════════════════════════════════════════════════════════════════
set -euo pipefail

# ── Config (override with env vars) ──────────────────────────────────────────
APP_HOME="${APP_HOME:-/opt/swift-platform}"
JAR_PATH="${JAR_PATH:-$APP_HOME/backend/swift-backend.jar}"
LOG_DIR="${LOG_DIR:-$APP_HOME/logs}"
PID_DIR="${PID_DIR:-$APP_HOME/run}"

# Backend JVM options (override via env)
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx2g -XX:+UseG1GC}"

# MongoDB (override via env — never hardcode here)
export MONGO_URI="${MONGO_URI:-mongodb://localhost:27017/swiftdb}"
export MONGO_DATABASE="${MONGO_DATABASE:-swiftdb}"

# JWT
export JWT_SECRET="${JWT_SECRET:-SwiftPlatformSecretKey2024!@#$%^&*()ABCDEF_MUST_BE_32_CHARS_MIN}"
export JWT_EXPIRATION_MS="${JWT_EXPIRATION_MS:-86400000}"

# CORS
export CORS_ALLOWED_ORIGINS="${CORS_ALLOWED_ORIGINS:-http://localhost:3000}"

# Server port
export SERVER_PORT="${SERVER_PORT:-8080}"

# ── Create dirs ───────────────────────────────────────────────────────────────
mkdir -p "$LOG_DIR" "$PID_DIR"

# ── Start Backend ─────────────────────────────────────────────────────────────
echo "[SWIFT] Starting backend on port $SERVER_PORT..."
nohup java $JAVA_OPTS \
  -jar "$JAR_PATH" \
  --spring.data.mongodb.uri="$MONGO_URI" \
  --server.port="$SERVER_PORT" \
  > "$LOG_DIR/backend.log" 2>&1 &

echo $! > "$PID_DIR/backend.pid"
echo "[SWIFT] Backend PID: $(cat $PID_DIR/backend.pid) — log: $LOG_DIR/backend.log"

# ── Reload NGINX (serves built frontend) ─────────────────────────────────────
echo "[SWIFT] Reloading NGINX..."
nginx -t && nginx -s reload
echo "[SWIFT] NGINX reloaded."

echo ""
echo "══════════════════════════════════════════"
echo "  SWIFT Platform started"
echo "  Backend:  http://localhost:$SERVER_PORT"
echo "  Frontend: Check NGINX config for domain"
echo "══════════════════════════════════════════"
