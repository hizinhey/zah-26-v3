#!/usr/bin/env bash
# Smoke test for a running OpsHub Compose stack. Run after:
#   docker compose -f deploy/compose.yaml up -d
#
# Exercises the stack only through the public gateway (no direct backend
# or database access), matching the "no public backend/database ports"
# constraint.
set -uo pipefail

BASE_URL="${OPSHUB_SMOKE_BASE_URL:-http://127.0.0.1}"
HUB_ID="${OPSHUB_SMOKE_HUB_ID:-00000000-0000-0000-0000-000000000001}"
COMPOSE_FILE="${OPSHUB_COMPOSE_FILE:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/compose.yaml}"

failures=0
fail() { echo "FAIL: $1"; failures=$((failures + 1)); }
ok()   { echo "OK: $1"; }

# --- Frontend served through the gateway, with history fallback ---
if curl -fsS "$BASE_URL/" -o /dev/null; then
  ok "frontend root served through gateway"
else
  fail "frontend root not reachable through gateway"
fi

if curl -fsS "$BASE_URL/plans/deep-link-check" -o /dev/null; then
  ok "SPA deep link falls back to index.html"
else
  fail "SPA deep link did not fall back to index.html"
fi

# --- Backend readiness and database migration ---
# Spring Boot's readiness probe only reaches "accepting-traffic" after the
# application context has fully refreshed, which is after Flyway has
# migrated the schema and JPA has validated it against the entities
# (ddl-auto: validate). It is intentionally not exposed on the public
# gateway (actuator is an operational endpoint, not part of the API
# surface), so it's checked over the private Compose network instead.
if command -v docker >/dev/null 2>&1; then
  readiness_body=$(docker compose -f "$COMPOSE_FILE" exec -T backend \
    curl -fsS http://127.0.0.1:8080/actuator/health/readiness 2>/dev/null || echo "")
  if echo "$readiness_body" | grep -q '"status":"UP"'; then
    ok "backend readiness is UP (Flyway migration + JPA schema validation succeeded)"
  else
    fail "backend readiness is not UP: $readiness_body"
  fi
else
  fail "docker not found; cannot check backend readiness"
fi

# --- REST proxy of a real API route (an application-level response, not a gateway error) ---
# POST /api/v1/operations exists per contracts/openapi/opshub-v1.yaml; an empty/invalid body is
# expected to be rejected by the backend (400/401/403), proving the request reached it rather
# than being swallowed by the gateway (which would show up as 502/504/000).
operations_status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/operations" 2>/dev/null || echo "000")
if [[ "$operations_status" =~ ^(200|201|400|401|403|405)$ ]]; then
  ok "/api/v1/operations reaches the backend (status $operations_status)"
else
  fail "/api/v1/operations did not reach the backend (status $operations_status)"
fi

# --- WebSocket upgrade reaches the backend through /ws/ ---
if command -v curl >/dev/null 2>&1; then
  ws_headers=$(curl -sS -o /dev/null -D - \
    -H "Connection: Upgrade" -H "Upgrade: websocket" \
    -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: c21vb3RoIGdhdGV3YXk=" \
    "$BASE_URL/ws/v1/hubs/$HUB_ID" 2>/dev/null || true)
  if echo "$ws_headers" | grep -qi "^HTTP/.* 101"; then
    ok "WebSocket upgrade to /ws/v1/hubs/{hubId} succeeded (101 Switching Protocols)"
  elif echo "$ws_headers" | grep -qi "^HTTP/.* 401\|^HTTP/.* 403"; then
    ok "WebSocket route reaches the backend and is authenticated (status line: $(echo "$ws_headers" | head -n1 | tr -d '\r'))"
  else
    fail "WebSocket upgrade did not reach the backend as expected: $(echo "$ws_headers" | head -n1 | tr -d '\r')"
  fi
else
  fail "curl not found; cannot test WebSocket upgrade"
fi

# --- No public backend/database ports ---
# `docker compose port <service> <port>` prints a real host:port mapping (e.g.
# "0.0.0.0:5432") only when the port is actually published; for an unpublished
# port it prints a placeholder like "invalid IP:0" instead of failing, so match
# on a real IP:port pattern rather than emptiness/exit code.
if command -v docker >/dev/null 2>&1; then
  published=$(docker compose -f "$COMPOSE_FILE" port backend 8080 2>/dev/null || true)
  if echo "$published" | grep -qE '^[0-9.]+:[0-9]+$|^\[.*\]:[0-9]+$'; then
    fail "backend port 8080 is unexpectedly published: $published"
  else
    ok "backend port 8080 is not published to the host"
  fi
  published_db=$(docker compose -f "$COMPOSE_FILE" port postgres 5432 2>/dev/null || true)
  if echo "$published_db" | grep -qE '^[0-9.]+:[0-9]+$|^\[.*\]:[0-9]+$'; then
    fail "postgres port 5432 is unexpectedly published: $published_db"
  else
    ok "postgres port 5432 is not published to the host"
  fi
fi

echo
if [[ "$failures" -eq 0 ]]; then
  echo "SMOKE TEST PASSED"
  exit 0
else
  echo "$failures SMOKE CHECK(S) FAILED"
  exit 1
fi
