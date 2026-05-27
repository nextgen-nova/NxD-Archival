#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════════════════
#  Build all frontend MFEs
#  Reads REACT_APP_API_BASE_URL from environment (or .env files per MFE).
#  Override the backend URL without touching any code:
#    REACT_APP_API_BASE_URL=https://api.mycompany.com ./build-frontend.sh
# ══════════════════════════════════════════════════════════════════════════════
set -euo pipefail

FRONTEND_DIR="${FRONTEND_DIR:-$(dirname "$0")/../frontend}"
FRONTEND_DIR=$(realpath "$FRONTEND_DIR")

build_mfe() {
    local name=$1
    local dir="$FRONTEND_DIR/$name"
    echo ""
    echo "── Building $name ──────────────────────────────"
    cd "$dir"
    npm install --prefer-offline
    npm run build
    echo "✓  $name built → $dir/dist"
}

build_mfe "mfe-user-management"
build_mfe "mfe-search"
build_mfe "mfe-profile"
build_mfe "shell-app"

echo ""
echo "══════════════════════════════════════════════════"
echo "  All MFEs built successfully."
echo "  Deploy each dist/ folder per your NGINX config."
echo "══════════════════════════════════════════════════"
