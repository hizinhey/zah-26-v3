# Unified Multi-Platform Hub Design

## Problem

Today, "Hub" identity and "platform" are the same thing: `hubs.platform` is a
single column (`CHECK (platform IN ('ANDROID', 'WEB'))`), `job_leases` has a
hard `UNIQUE(hub_id)` constraint (at most one lease per Hub, ever), and the
Local Hub Python process reads `HubConfig.platform` once at startup and never
changes it. Android runs as a manually-started, always-on Local Hub process;
Web runs as a separate process the backend spawns on demand
(`WebWorkerLauncher`), tracked as a single `Process` field with its own
distinct `hub_id`.

The operator wants one Local Hub deployment, on one machine that has both an
Android device and Chrome available, to run an Android test case and a Web
test case at the same time — without restarting the Hub or the backend to
add/switch platforms. They also want this to appear as one Hub in the
operator UI (one identity, multiple platform readiness rows), not two
separate Hub rows that happen to run concurrently by coincidence.

## Goal

Let a single Hub identity (one `hub_id`) run independent, concurrent sessions
for multiple platforms (Android + Web to start) inside one long-lived Local
Hub process. Each platform's session is fully isolated — a broken Android
device must not block the Web session from starting, and vice versa — and
adding/removing a platform from a running Hub's configured set never requires
a backend restart.

iOS is explicitly out of scope for this spec (see sub-project 1, deferred
until this lands — it will plug into this design as a third entry in the
Hub's configured platform list).

## Data model (migration V8)

- `hubs` shrinks to a pure identity row: `id, name, created_at`. The
  `connection_status`, `transport`, `last_heartbeat_at`, `device_ready`,
  `runner_ready`, and `platform` columns move to a new table below — a Hub
  itself has no single connection state once it can run multiple concurrent,
  independently-failing platform sessions.
- New `hub_platforms` table: `hub_id, platform, connection_status,
  transport, last_heartbeat_at, device_ready, runner_ready`, primary key
  `(hub_id, platform)`. One row per platform a Hub is currently (or was
  last) running, updated independently of every other platform's row.
- `job_leases` gains a `platform` column (`NOT NULL`). The unique constraint
  moves from `job_leases_hub_id_unique UNIQUE(hub_id)` to
  `UNIQUE(hub_id, platform)` — this is the actual concurrency unlock: a Hub
  can hold one active ANDROID lease and one active WEB lease simultaneously,
  but never two leases for the same platform.
- This is a clean, non-backward-compatible migration (existing `hubs` rows'
  single platform/connection fields are backfilled into `hub_platforms`,
  then the old columns are dropped from `hubs`), consistent with how prior
  migrations in this repo (e.g. V6) have been done. No dual-write
  transition period, since this is pre-production.

## Wire protocol

Unchanged in shape. `X-Hub-Platform` is already sent on every relevant call
(WS handshake headers, `/jobs/next`, `/heartbeat`) — the gap was never the
protocol, it was that the backend collapsed all of it into a single
`hubs.platform` column and a single `job_leases` row per Hub. Two concurrent
platform sessions under one `hub_id` now show up as two independent
WebSocket connections (or polling loops) to the same `hub_id`, each
declaring its own platform at handshake/request time, exactly as today —
just no longer limited to one at a time.

## Backend changes

- `HubConnectionService.markOnline`/`heartbeat`/`markOffline`: upsert into
  `hub_platforms` keyed by `(hubId, platform)` instead of overwriting
  `hubs`' single-value columns. (This also fixes a latent bug: today, if two
  platforms' heartbeats ever hit the same `hub_id`, they'd stomp each
  other's reported readiness.)
- `LeaseService`: `hasActiveLease`, `acquire`, `renewActiveLease` all take an
  explicit `platform` parameter and filter/insert against the new
  `(hub_id, platform)` key.
- `ExecutionService.offerNextJob(hubId, platform)`: takes the caller-supplied
  platform (already available at every call site) instead of deriving it by
  reading `hubs.platform`. Validates the Hub has a known `hub_platforms` row
  for that platform, checks `hasActiveLease(hubId, platform)`, and dispatches
  against that platform's queue exactly as `nextOfferableExecution` does
  today.
- `HubWebSocketHandler.sessionsByHub`: currently dead code outside its own
  class (write-only, never read elsewhere) and keyed by `hubId` alone —
  becomes keyed by `(hubId, platform)` since two concurrent WS connections
  now legitimately share a `hub_id`.
- `WebWorkerLauncher` is retired entirely. Web stops being spawned on demand
  by the backend and becomes one of the sessions the merged Local Hub
  process runs continuously, exactly like Android does today.
  `ExecutionService.start()` drops its `webWorkerLauncher.launchIfNeeded()`
  call and the WEB-platform branch that triggers it.
  `WebWorkerProperties`/`opshub.web-worker.*` config is removed.
- `HubQueryService.listHubs()`/`HubSummary`: returns one row per Hub with a
  `platforms: List<PlatformStatus>` field (each entry: platform, connection
  status, transport, device/runner readiness, last heartbeat) instead of
  flat `platform`/`deviceReady`/`runnerReady` fields.

## Local Hub (Python) changes

This is the smallest part structurally — almost none of today's per-platform
code changes:

- `HubConfig.platform: Literal["ANDROID","WEB"]` becomes
  `platforms: list[Literal["ANDROID","WEB"]]`, loaded from a new
  `OPSHUB_PLATFORMS` env var (comma-separated, e.g. `ANDROID,WEB`), replacing
  `OPSHUB_PLATFORM`. Same shared `hub_id`/`hub_token` used by every platform.
- `main.run_forever()`: instead of building one `Runner` and looping once,
  spins up one thread per configured platform. Each thread runs today's
  existing per-platform code completely unchanged — its own preflight
  (`run_preflight` or `run_web_preflight`), its own `Runner`
  (`build_runner`/`build_web_runner`), its own `FailoverTransport`/
  `WebSocketTransport` (connecting with that platform's own header), its own
  `while True` receive-execute-heartbeat loop. A platform whose preflight
  fails logs and stops just that thread; the others keep running.
- `Runner`, `templates.py`, `appium_control.py`, `browser_control.py`,
  `preflight.py`, the transport modules, and the template catalogs are
  **unchanged** — this design reuses them as-is, just runs more than one
  copy of the existing single-platform pipeline concurrently in one process.

## Frontend changes

- `HubStatusIndicator`: currently only ever renders `hubs?.[0]` with flat
  `platform`/`deviceReady`/`runnerReady` fields (already a bit of a
  single-hub/single-platform assumption baked in). Reworks to show one Hub
  with N platform rows (status, device, runner, last heartbeat per
  platform), driven by the new `platforms` array.

## Contracts

- `contracts/openapi/opshub-v1.yaml` / `contracts/schemas/hub-envelope-v1.json`:
  `HubSummary` changes from flat platform/readiness fields to a `platforms`
  array (one entry per platform). No change to `JobOfferedPayload`,
  `HeartbeatPayload`, or any other envelope shape — those already carry (or
  are already scoped by) platform per-connection.

## Rollout

Existing deployments: the operator stops the Android Hub process and the
`opshub.web-worker.enabled` config, migrates the database (V8), then starts
one Local Hub process with `OPSHUB_PLATFORMS=ANDROID,WEB` pointed at the
same host that has both the Android device and a provisioned Chrome
profile. No dual-running/compatibility period is planned.

## Out of scope

- iOS platform support (sub-project 1 — deferred until this lands, then
  plugs in as a third `OPSHUB_PLATFORMS` entry with no further Hub
  concurrency work needed).
- PC platform.
- Mixing platforms within one Operation (still enforced at OA save time,
  unrelated to Hub concurrency).
- More than one concurrent session of the *same* platform (e.g. two Android
  devices on one Hub) — one session per platform per Hub, matching "one
  Android device + one Chrome profile" as the natural resource constraint.
- Automatic simulator/emulator/device provisioning.
