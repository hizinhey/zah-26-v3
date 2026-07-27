# OpsHub — Android MVP

OpsHub automates QC of Zalo Official Account (OA) campaigns on Android:
operators enter OA details, get AI-assisted content validation, generate a
fixed five-case test plan per OA, approve it, and run it end to end on a
real Android device through a Python "Local Hub" driving Appium/WebdriverIO.
Android is the only platform in this MVP.

This repository contains four components:

| Component | Path | Stack |
|---|---|---|
| Backend | `backend/` | Spring Boot, PostgreSQL, Flyway |
| Frontend | `frontend/` | React, TypeScript, Vite, TanStack Query |
| Local Hub | `local-hub/` | Python, Pydantic, Appium/WebdriverIO templates |
| Deployment | `deploy/` | Docker Compose, Nginx gateway, Rocky Linux 9 target |

Shared contracts (REST OpenAPI, the Hub WebSocket/HTTP envelope schema,
template parameters) live in `contracts/` and are the source of truth both
the backend and the Local Hub are tested against
(`contracts/tests/test_contract_examples.py`,
`local-hub/tests/integration/test_backend_contract.py`).

See `handover/README.md` for product/design documents, mockups, and
implementation-plan history.

## First startup (local development)

```bash
# Backend + database + gateway + frontend, containerized:
cp deploy/env/backend.env.example deploy/env/backend.env
$EDITOR deploy/env/backend.env   # fill in the variables listed below
bash deploy/scripts/preflight.sh
docker compose -f deploy/compose.yaml up -d --build
bash deploy/scripts/smoke.sh
```

Then, separately, start a Local Hub against that backend (see
`docs/operations/local-hub-runbook.md` section 1 for the full procedure):

```bash
cd local-hub
python3 -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
cp .env.example .env
$EDITOR .env
python -m opshub_hub.main
```

For a production deployment on Rocky Linux 9, follow
`docs/deployment/rocky-linux-9.md` from the start (it covers Docker
installation, firewall, SELinux, persistent data directories, and TLS,
which the snippet above assumes are already in place).

## Environment variables (names only — no secret values here)

Backend / deployment (`deploy/env/backend.env`, git-ignored):

| Variable | Purpose |
|---|---|
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Database credentials |
| `OPSHUB_HUB_TOKEN` | Shared bearer token Hubs present on every Hub-facing request; must match the Local Hub's `OPSHUB_HUB_TOKEN` |
| `OPSHUB_HUB_POLL_WAIT_CAP_SECONDS` | Upper bound on long-poll wait time for `/jobs/next` |
| `OPSHUB_EVIDENCE_MAX_BYTES` | Max accepted evidence file size |
| `GEMINI_API_KEY`, `GEMINI_MODEL`, `GEMINI_BASE_URL` | Optional Gemini-backed text/thumbnail validation; leave blank to disable |

Local Hub (`local-hub/.env`, git-ignored):

| Variable | Purpose |
|---|---|
| `OPSHUB_BACKEND_URL` | Base URL of the OpsHub backend |
| `OPSHUB_HUB_ID` | This Hub's identifier |
| `OPSHUB_HUB_TOKEN` | Must match the backend's `OPSHUB_HUB_TOKEN` |
| `OPSHUB_TEMPLATE_DIR` | Path to the Android WebdriverIO template catalog |
| `OPSHUB_WORK_DIR` | Writable data root (Outbox, rendered specs, evidence staging, journal) |

Full details, including how each is validated, are in
`docs/operations/local-hub-runbook.md` and
`docs/deployment/rocky-linux-9.md`.

## Hub/device preflight and starting Appium

Before executing any job, the Local Hub checks `node`/`adb` are installed,
exactly one device is visible to `adb devices`, Appium is reachable, the
Zalo package is installed on the device, the template catalog checksum is
intact, and its data directories are writable. Appium itself must be
started separately:

```bash
appium --address 127.0.0.1 --port 4723
```

See `docs/operations/local-hub-runbook.md` sections 3–4 for the full
preflight procedure, manual diagnostic commands, and recovery steps.

## Transport status

The Local Hub prefers a WebSocket connection to the backend
(`/ws/v1/hubs/{hubId}`) and automatically falls back to HTTPS long-polling
after three consecutive WebSocket failures, retrying the WebSocket
periodically until it reconnects. The browser (React frontend) side has no
backend-provided WebSocket endpoint yet — a documented, deliberately
deferred minor from Task 11 — so it always uses REST polling of
`GET /api/v1/executions/{executionId}` and surfaces that state in the UI.
See `docs/operations/local-hub-runbook.md` section 5.

## Evidence cleanup

Evidence (screenshots/logs) is written locally by the Hub and deleted only
after the backend acknowledges the upload; a failed upload always leaves
the local copy in place. Uploaded evidence lives under the backend's
evidence bind mount. See `docs/operations/local-hub-runbook.md` section 6
for cleanup commands.

## Database backup and service restart

```bash
docker compose -f deploy/compose.yaml exec -T postgres \
  pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" > opshub-$(date +%Y%m%d).sql
docker compose -f deploy/compose.yaml restart backend   # or any single service
```

See `docs/operations/local-hub-runbook.md` sections 7–9 for the full backup
procedure, service restart semantics, and a table of common recovery
commands (device offline, Appium unreachable, stuck lease, failing evidence
upload, etc.).

## Verification

```bash
./mvnw -pl backend test
npm --prefix frontend test -- --run && npm --prefix frontend run build && npm --prefix frontend run e2e
python -m pytest contracts/tests local-hub/tests -q
bash deploy/scripts/smoke.sh
```

The acceptance record for the full MVP lifecycle, including what could and
could not be verified in a given environment (Docker/Testcontainers,
physical-device/Appium hardware), is in
`docs/acceptance/android-mvp-checklist.md`.

## Documentation index

- [`docs/acceptance/android-mvp-checklist.md`](docs/acceptance/android-mvp-checklist.md) — Task 13 acceptance record
- [`docs/operations/local-hub-runbook.md`](docs/operations/local-hub-runbook.md) — Local Hub operator runbook
- [`docs/deployment/rocky-linux-9.md`](docs/deployment/rocky-linux-9.md) — production deployment guide
- [`handover/README.md`](handover/README.md) — product/design documents, mockups, implementation-plan history
