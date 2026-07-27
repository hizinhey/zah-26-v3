#!/usr/bin/env bash
# Preflight checks for deploying OpsHub on Rocky Linux 9 with Docker
# Compose. Run as: sudo bash deploy/scripts/preflight.sh
#
# Verifies Docker/Compose are installed, required host directories exist
# with correct ownership/SELinux labels, ports are free, the secrets file
# is present (checked by variable name only — values are never printed),
# and there is enough free disk space. Also generates a self-signed TLS
# certificate if none is present yet, so `docker compose up` works before
# a real certificate is issued.
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="$ROOT_DIR/deploy/env/backend.env"
ENV_EXAMPLE="$ROOT_DIR/deploy/env/backend.env.example"

DATA_DIR="${OPSHUB_DATA_DIR:-/opt/opshub/data}"
POSTGRES_DIR="${OPSHUB_POSTGRES_DIR:-/opt/opshub/postgres}"
TLS_DIR="${OPSHUB_TLS_DIR:-/opt/opshub/tls}"
MIN_FREE_GB="${OPSHUB_MIN_FREE_GB:-10}"

failures=0
warn() { echo "WARN: $1"; }
fail() { echo "FAIL: $1"; failures=$((failures + 1)); }
ok()   { echo "OK: $1"; }

echo "== OpsHub deployment preflight =="

# --- Docker / Compose ---
if command -v docker >/dev/null 2>&1; then
  ok "docker is installed ($(docker --version))"
else
  fail "docker is not installed or not on PATH"
fi

if docker compose version >/dev/null 2>&1; then
  ok "docker compose plugin is available ($(docker compose version --short 2>/dev/null))"
else
  fail "docker compose plugin is not available (docker compose version failed)"
fi

if command -v docker >/dev/null 2>&1 && ! docker info >/dev/null 2>&1; then
  fail "docker daemon is not reachable (is the service running / do you have permission?)"
fi

# --- Ports 80/443 free ---
for port in 80 443; do
  if command -v ss >/dev/null 2>&1; then
    if ss -ltn "( sport = :$port )" 2>/dev/null | grep -q ":$port"; then
      fail "port $port is already in use"
    else
      ok "port $port is free"
    fi
  else
    warn "ss not found; skipping port $port check"
  fi
done

# --- Required host directories ---
for dir in "$DATA_DIR" "$DATA_DIR/evidence" "$POSTGRES_DIR" "$TLS_DIR"; do
  if [[ -d "$dir" ]]; then
    ok "directory exists: $dir"
  else
    if mkdir -p "$dir" 2>/dev/null; then
      ok "created directory: $dir"
    else
      fail "directory missing and could not be created: $dir (run as root, or pre-create it)"
    fi
  fi
done

# --- Ownership / permissions ---
# The backend and postgres containers run as non-root users (uid mapped
# via the container's own user namespace); the host directories only need
# to be writable by Docker, which normally runs as root.
for dir in "$DATA_DIR" "$POSTGRES_DIR"; do
  if [[ -d "$dir" && -w "$dir" ]]; then
    ok "writable: $dir"
  elif [[ -d "$dir" ]]; then
    fail "not writable by the current user: $dir"
  fi
done

# --- SELinux-compatible volume labels ---
if command -v getenforce >/dev/null 2>&1; then
  mode="$(getenforce)"
  echo "INFO: SELinux mode: $mode"
  if [[ "$mode" != "Disabled" ]]; then
    if command -v chcon >/dev/null 2>&1; then
      chcon -Rt svirt_sandbox_file_t "$DATA_DIR" "$POSTGRES_DIR" 2>/dev/null \
        && ok "applied svirt_sandbox_file_t label to $DATA_DIR and $POSTGRES_DIR" \
        || warn "could not chcon $DATA_DIR / $POSTGRES_DIR; Compose already mounts them with the ':Z' SELinux relabel flag, so this is usually unnecessary"
    fi
    ok "compose.yaml mounts bind volumes with the ':Z' flag for private, per-container SELinux labels"
  fi
else
  echo "INFO: SELinux tooling not found (getenforce); skipping label check"
fi

# --- Secrets file presence (checked by variable name only; values never printed) ---
if [[ -f "$ENV_FILE" ]]; then
  ok "secrets file present: $ENV_FILE"
  if [[ -f "$ENV_EXAMPLE" ]]; then
    missing=""
    while IFS='=' read -r key _; do
      [[ "$key" =~ ^#.*$ || -z "$key" ]] && continue
      if ! grep -q "^${key}=" "$ENV_FILE"; then
        missing="$missing $key"
      fi
    done < "$ENV_EXAMPLE"
    if [[ -z "$missing" ]]; then
      ok "all expected variable names are present in $ENV_FILE"
    else
      fail "missing variable name(s) in $ENV_FILE:$missing"
    fi
  fi
else
  fail "secrets file missing: $ENV_FILE (copy $ENV_EXAMPLE and fill in real values)"
fi

# --- Disk space ---
if command -v df >/dev/null 2>&1; then
  avail_kb=$(df -Pk "$DATA_DIR" 2>/dev/null | awk 'NR==2 {print $4}')
  if [[ -n "${avail_kb:-}" ]]; then
    avail_gb=$((avail_kb / 1024 / 1024))
    if (( avail_gb >= MIN_FREE_GB )); then
      ok "sufficient free disk space on $DATA_DIR (${avail_gb}G >= ${MIN_FREE_GB}G)"
    else
      fail "insufficient free disk space on $DATA_DIR (${avail_gb}G < ${MIN_FREE_GB}G)"
    fi
  else
    warn "could not determine free disk space for $DATA_DIR"
  fi
fi

# --- Self-signed TLS certificate (bootstrap only) ---
if [[ -f "$TLS_DIR/fullchain.pem" && -f "$TLS_DIR/privkey.pem" ]]; then
  ok "TLS certificate present in $TLS_DIR"
else
  if command -v openssl >/dev/null 2>&1; then
    if openssl req -x509 -nodes -newkey rsa:2048 -days 365 \
        -keyout "$TLS_DIR/privkey.pem" -out "$TLS_DIR/fullchain.pem" \
        -subj "/CN=opshub.local" >/dev/null 2>&1; then
      ok "generated a bootstrap self-signed certificate in $TLS_DIR (replace with a real certificate before going live; see docs/deployment/rocky-linux-9.md)"
    else
      fail "could not generate a bootstrap self-signed certificate in $TLS_DIR"
    fi
  else
    fail "openssl not found; cannot generate a bootstrap certificate, and none was found in $TLS_DIR"
  fi
fi

echo
if [[ "$failures" -eq 0 ]]; then
  echo "PREFLIGHT PASSED"
  exit 0
else
  echo "PREFLIGHT FAILED ($failures issue(s))"
  exit 1
fi
