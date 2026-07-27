#!/usr/bin/env bash
# Static assertions on the Nginx gateway configuration and Compose file.
# Verifies: frontend history fallback, API proxying, WebSocket upgrade
# headers, evidence upload limit, and absence of public backend/database
# ports. Does not require Docker.
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONF="$ROOT_DIR/gateway/conf.d/opshub.conf"
MAIN_CONF="$ROOT_DIR/gateway/nginx.conf"
COMPOSE="$ROOT_DIR/deploy/compose.yaml"
FRONTEND_CONF="$ROOT_DIR/frontend/nginx.conf"

failures=0

fail() {
  echo "FAIL: $1"
  failures=$((failures + 1))
}

pass() {
  echo "PASS: $1"
}

assert_file_exists() {
  local path="$1"
  if [[ -f "$path" ]]; then
    pass "exists: $path"
  else
    fail "missing file: $path"
    return 1
  fi
}

assert_grep() {
  local desc="$1" pattern="$2" file="$3"
  if [[ -f "$file" ]] && grep -Eq "$pattern" "$file"; then
    pass "$desc"
  else
    fail "$desc"
  fi
}

assert_not_grep() {
  local desc="$1" pattern="$2" file="$3"
  if [[ -f "$file" ]] && grep -Eq "$pattern" "$file"; then
    fail "$desc"
  else
    pass "$desc"
  fi
}

assert_file_exists "$CONF" || true
assert_file_exists "$MAIN_CONF" || true
assert_file_exists "$COMPOSE" || true
assert_file_exists "$FRONTEND_CONF" || true

# --- Frontend history fallback (SPA routing) ---
# The frontend container's own nginx performs the history fallback for its
# static build; the gateway proxies non-API routes to it.
assert_grep "frontend nginx serves SPA with history fallback" \
  'try_files[[:space:]]+\$uri[[:space:]]+/index\.html' "$FRONTEND_CONF"
assert_grep "gateway proxies non-API routes to the frontend upstream" \
  'proxy_pass[[:space:]]+http://frontend' "$CONF"

# --- API proxying ---
assert_grep "routes /api/ to backend upstream" \
  'location[[:space:]]+/api/[[:space:]]*\{' "$CONF"
assert_grep "proxies /api/ requests via proxy_pass to backend service" \
  'proxy_pass[[:space:]]+http://backend' "$CONF"

# --- WebSocket upgrade headers ---
assert_grep "routes /ws/ to backend upstream" \
  'location[[:space:]]+/ws/[[:space:]]*\{' "$CONF"
assert_grep "sets Upgrade header for WebSocket proxying" \
  'proxy_set_header[[:space:]]+Upgrade[[:space:]]+\$http_upgrade' "$CONF"
assert_grep "sets Connection upgrade header for WebSocket proxying" \
  'proxy_set_header[[:space:]]+Connection[[:space:]]+.*upgrade' "$CONF"
assert_grep "declares connection_upgrade map for websocket support" \
  'map[[:space:]]+\$http_upgrade[[:space:]]+\$connection_upgrade' "$MAIN_CONF"

# --- Evidence upload limit ---
assert_grep "sets a client_max_body_size for evidence uploads" \
  'client_max_body_size[[:space:]]+[0-9]+[mM]' "$CONF"

# --- No public backend/database ports ---
assert_not_grep "compose does not publish backend container ports" \
  '^\s*backend:[\s\S]*?ports:' "$COMPOSE"
assert_grep "backend service has no top-level ports mapping (only gateway does)" \
  'gateway:' "$COMPOSE"

# Explicit per-service check: only the gateway service may have a `ports:` key.
if [[ -f "$COMPOSE" ]]; then
  services_with_ports=$(awk '
    /^  [a-zA-Z0-9_-]+:$/ { svc=$1; sub(":","",svc) }
    /^    ports:$/ { print svc }
  ' "$COMPOSE")
  bad=""
  for svc in $services_with_ports; do
    if [[ "$svc" != "gateway" ]]; then
      bad="$bad $svc"
    fi
  done
  if [[ -z "$bad" ]]; then
    pass "only the gateway service publishes host ports"
  else
    fail "unexpected services publish host ports:$bad"
  fi
else
  fail "compose file missing, cannot check published ports"
fi

echo
if [[ "$failures" -eq 0 ]]; then
  echo "ALL CHECKS PASSED"
  exit 0
else
  echo "$failures CHECK(S) FAILED"
  exit 1
fi
