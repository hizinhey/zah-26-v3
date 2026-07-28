# Local Hub Operations Runbook

Operator reference for running the Python Local Hub (`local-hub/`) against a
running OpsHub backend, covering first startup, environment configuration,
device/Appium preflight, transport status, evidence lifecycle, and common
recovery steps. No secret *values* appear in this document — only variable
*names*.

## 1. First startup

```bash
cd local-hub
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
cp .env.example .env
$EDITOR .env   # fill in the variables listed in section 2
python -m opshub_hub.main
```

On startup the Hub:

1. Loads and validates its configuration (refuses to start if any variable
   is missing — see section 2).
2. Runs preflight checks (section 4). If any check fails, the Hub logs each
   failure and exits without attempting to run jobs.
3. Connects to the backend, preferring the WebSocket transport and falling
   back to HTTPS long-polling automatically (section 5).
4. Begins sending heartbeats and waits for a job offer.

## 2. Environment variables

Set in `local-hub/.env` (git-ignored; never commit it). Names only — no
values:

| Variable | Purpose |
|---|---|
| `OPSHUB_BACKEND_URL` | Base URL of the OpsHub backend (e.g. `https://opshub.example.internal`) |
| `OPSHUB_HUB_ID` | This Hub's identifier, used in both transport URLs |
| `OPSHUB_HUB_TOKEN` | Shared bearer token presented on every Hub-facing request (`X-Hub-Token` header / WebSocket `token` query param). Must match `OPSHUB_HUB_TOKEN` in `deploy/env/backend.env` on the backend side |
| `OPSHUB_TEMPLATE_DIR` | Path to the WebdriverIO template catalog (`local-hub/templates/android` in this repo) |
| `OPSHUB_WORK_DIR` | Writable data root for the Outbox database, rendered specs, evidence staging, and journal |
| `OPSHUB_WDIO_PROJECT_DIR` | A real, installed WebdriverIO project (`wdio.conf.ts`, `tsconfig.json`, `node_modules`) that every execution's rendered spec is run against — e.g. this repo's `mobile_script/` directory, installed locally per runner host, never committed. Without this the runner has no config or dependencies to run a spec with |
| `OPSHUB_NODE_EXECUTABLE` | Node.js 20+ binary used to run the pinned WebdriverIO CLI directly, bypassing whatever `node` (if any, and whatever version) happens to be first on `PATH` |

Related, but not read by the Hub process itself:

| Variable | Purpose |
|---|---|
| `MOBILE_SCRIPT_NODE_MODULES` | Points test tooling (`pytest`, template typecheck tests) at the ignored WebdriverIO `node_modules` directory under `mobile_script/` — never committed, must be installed locally per device/runner host |
| `MOBILE_SCRIPT_NODE` | Overrides which `node` binary the same tests use, if not the one on `PATH` |

## 3. Hub and device preflight

Before executing any job, the Hub runs (`opshub_hub.preflight.run_preflight`):

- **Executable versions** — `OPSHUB_NODE_EXECUTABLE` and `adb` must run.
- **ADB device state** — exactly one authorized device/emulator visible to
  `adb devices`.
- **Appium reachability** — `GET http://127.0.0.1:4723/status` (or the
  configured Appium status URL) must respond.
- **Zalo package installed** — `adb shell pm list packages com.zing.zalo`
  must list the package on the connected device.
- **Template manifest integrity** — the template catalog's checksums must
  match its manifest.
- **WebdriverIO project installed** — `OPSHUB_WDIO_PROJECT_DIR` must contain
  `wdio.conf.ts`, `tsconfig.json`, and an installed `node_modules/.bin/wdio`.
- **Writable data directories** — `OPSHUB_WORK_DIR` and its subdirectories
  must be writable.

Run preflight standalone (without starting the full Hub loop) to diagnose a
device/Appium problem:

```bash
python -c "
from opshub_hub.preflight import run_preflight
from pathlib import Path
report = run_preflight(template_root=Path('templates/android'), data_root=Path('.data'))
for check in report.checks:
    print(('OK  ' if check.ok else 'FAIL'), check.name, check.detail)
"
```

Manual equivalents, for debugging a single failing check:

```bash
adb devices                                   # exactly one line besides the header, state "device"
curl -fsS http://127.0.0.1:4723/status        # Appium status
adb shell pm list packages com.zing.zalo      # Zalo installed
```

## 4. Starting Appium

The Hub does not start Appium itself — start it separately on the same host
before starting the Hub (or before it next needs to run a job):

```bash
appium --address 127.0.0.1 --port 4723
```

Confirm it is reachable with the preflight check above, or directly:

```bash
curl -fsS http://127.0.0.1:4723/status
```

## 5. Transport status (WebSocket vs. polling fallback)

The Hub prefers the WebSocket transport (`ws://…/ws/v1/hubs/{hubId}`) and
automatically fails over to HTTPS long-polling (`GET …/jobs/next`) after
three consecutive WebSocket failures (`opshub_hub.transport.failover`).
It periodically retries the WebSocket connection while polling and switches
back once it succeeds.

Check which transport is currently active from the Hub's structured log
output (`transport=WEBSOCKET` or `transport=HTTPS_POLLING`), or ask the
backend directly:

```bash
# Requires network access to the private opshub-internal network, or a
# database client — this is an operational/debugging query, not a public
# endpoint.
psql -c "SELECT id, connection_status, transport, last_heartbeat_at, device_ready, runner_ready FROM hubs WHERE id = '<OPSHUB_HUB_ID>';"
```

A Hub that has not sent a heartbeat in a while, or shows
`connection_status = OFFLINE`, is not eligible to receive job offers — the
backend rejects `/api/v1/operations/{id}/executions` with
`HUB_NOT_ONLINE` until a heartbeat arrives on either transport.

The browser (React frontend) side has a separate, deliberately deferred
transport story: no browser-facing WebSocket endpoint exists on the
backend yet, so the frontend always falls back to REST polling of
`GET /api/v1/executions/{executionId}` every ~3 seconds
(`frontend/src/realtime/useExecutionChannel.ts`). This is a documented,
intentionally scoped-down minor from Task 11, not a defect — the Execute
screen shows "Connecting to live updates…" then "Live updates
disconnected — refreshing periodically." once the fallback engages.

## 6. Evidence cleanup

Evidence (screenshots, logs) is written locally under
`OPSHUB_WORK_DIR/<executionId>/evidence/` before being uploaded to the
backend, and is only deleted locally once the backend acknowledges the
upload (`opshub_hub.evidence.EvidenceUploader`) — a failed upload leaves the
local file in place so nothing is lost.

To manually reclaim disk space for executions that are known-complete and
already uploaded:

```bash
# Review before deleting - only remove executions you have confirmed are
# COMPLETED and fully uploaded (check GET /api/v1/executions/{id} on the
# backend, or the Hub's journal).
find "$OPSHUB_WORK_DIR" -maxdepth 1 -type d -mtime +7
rm -rf "$OPSHUB_WORK_DIR/<executionId>"
```

On the backend side, uploaded evidence lives under the `OPSHUB_DATA_DIR`
bind mount (`/opt/opshub/data/evidence` in the Rocky Linux deployment, see
`docs/deployment/rocky-linux-9.md`) — cleanup there is a backend/operator
concern, not the Hub's.

## 7. Database backup

Backups are a backend/deployment concern (the Hub itself is stateless
except for its local Outbox/journal). See
`docs/deployment/rocky-linux-9.md` section 11 for the full procedure;
summary:

```bash
docker compose -f deploy/compose.yaml exec -T postgres \
  pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" > opshub-$(date +%Y%m%d).sql
```

Also back up the Hub's local Outbox database (`OPSHUB_WORK_DIR/outbox.db`)
if you need to preserve in-flight, not-yet-flushed envelopes across a Hub
host migration — under normal operation it drains automatically and does
not need routine backup.

## 8. Service restart

Restarting the Hub process is always safe: the Outbox durably persists any
envelope that could not be sent, and replays it on the next successful
`flush()` once the Hub reconnects (either transport). No job state is lost
by restarting the Hub mid-execution — the backend simply does not hear from
that Hub until it reconnects and its lease is renewed or expires.

```bash
# Stop (Ctrl-C or):
pkill -f "opshub_hub.main"

# Start again:
cd local-hub && source .venv/bin/activate && python -m opshub_hub.main
```

Restarting the backend/gateway/database stack is documented in
`docs/deployment/rocky-linux-9.md` sections 8–10 (`docker compose up -d`
after a `build`, or `docker compose restart <service>` for a single
service without rebuilding).

## 9. Common recovery commands

| Symptom | Command | Notes |
|---|---|---|
| Hub won't start, missing config | check `local-hub/.env` has every variable in section 2 | the Hub refuses to start rather than run with partial config |
| Preflight fails on `adb-device-state` | `adb kill-server && adb start-server && adb devices` | most common cause: device asleep, USB debugging revoked, or more/fewer than one device attached |
| Preflight fails on `appium-reachable` | restart Appium (section 4), then re-run preflight | check nothing else is bound to port 4723 |
| Preflight fails on `zalo-package-installed` | reinstall/re-verify the Zalo build on the device, confirm campaign preconditions and login are already in place per the test plan | out of scope for the Hub to remediate automatically |
| Hub stuck on `HTTPS_POLLING`, never reconnects to WebSocket | check the backend/gateway WebSocket route is reachable (`curl -i` a WS upgrade against `/ws/v1/hubs/{hubId}`); check `OPSHUB_HUB_TOKEN` matches on both sides | failover retries WebSocket automatically every poll interval - no manual re-enable needed once the path is fixed |
| Execution stuck `RUNNING` in the UI, Hub log shows nothing | check `hubs.connection_status`/`last_heartbeat_at` (section 5); if `OFFLINE`, the lease will expire and the job becomes offerable again automatically | do not manually clear the lease row; let the TTL expire |
| Evidence upload failing repeatedly | check `OPSHUB_EVIDENCE_MAX_BYTES` on the backend against the local file size; check backend disk space under the evidence bind mount | local files are preserved until upload succeeds, so retrying later is always safe |
| Need to re-run preflight only | see the standalone snippet in section 3 | does not require starting the full Hub loop |
