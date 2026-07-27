# OpsHub Android MVP — Acceptance Checklist

Task 13 acceptance record. Android is the only platform in scope; every OA
generates exactly five fixed test cases in order; only `PASSED` findings
satisfy generation gating; every OA mutation/reorder increments the
operation revision and invalidates downstream state; execution continues
through every test and OA after an assertion failure; assertion failures
never retry, infrastructure errors retry exactly once. User authentication,
Jira ingestion, additional platforms, parallel execution, and individual-
case reruns are out of scope for this MVP.

## Automated coverage

| Item | Covered by | Status |
|---|---|---|
| Multiple OAs on one operation | `backend/.../acceptance/MvpLifecycleIT.java` (two OAs, ten cases) | Implemented, compiles; requires Docker for Testcontainers (see below) |
| Validation failure and correction | `MvpLifecycleIT`, `ValidationGatingIT` | Implemented |
| Five-case generation, in order, per OA | `MvpLifecycleIT`, `TestPlanServiceTest` | Implemented |
| Approval | `MvpLifecycleIT`, `TestPlanController` | Implemented |
| Stale revision rejection (OA write and execution start) | `MvpLifecycleIT`, `OperationControllerIT` | Implemented |
| Hub readiness gate | `MvpLifecycleIT` (lease renewal against an unregistered Hub → 409) | Implemented |
| Sequential execution (one active job leased per Hub) | `MvpLifecycleIT`, `HubProtocolIT` | Implemented |
| Continuation after an assertion failure | `MvpLifecycleIT`, `local-hub/tests/integration/test_backend_contract.py`, `local-hub/tests/test_runner.py` | Implemented |
| Exactly one infrastructure retry | same as above | Implemented |
| Evidence upload | `MvpLifecycleIT`, `EvidenceServiceTest` | Implemented end-to-end (backend endpoint + upload client + `Runner._capture_and_upload_evidence`), and `build_runner` now wires real capture/reset implementations (`opshub_hub.appium_control.AdbScreenshotCapturer`/`AppiumSessionResetter`, via `adb exec-out screencap` and the Appium server's own `/sessions` HTTP API — see C3 fix). Neither implementation has been exercised against a live physical device/Appium server in this environment; verified only by the `build_runner` wiring regression test (`local-hub/tests/test_main.py`) and unit-level review. Real on-device verification remains a gap. |
| Hub-facing WebSocket events | `MvpLifecycleIT`, `HubProtocolIT` | Implemented |
| Browser-facing REST-poll fallback | `frontend/e2e/mvp-lifecycle.spec.ts` | Implemented, passing |
| Local Hub ↔ backend wire-contract conformance (JSON Schema + exact field names) | `local-hub/tests/integration/test_backend_contract.py` | Implemented, passing |

## Verification run (this environment, this session)

Commands run exactly as specified, on `feature/opshub-android-mvp`, with
current results:

1. `./mvnw -pl backend test`
   - 53 pre-existing tests + `MvpLifecycleIT` (2 new tests): 53 unit-level
     tests **pass**. 9 `@Testcontainers` IT classes (including the new
     `MvpLifecycleIT`) **cannot run** in this sandbox: the Docker daemon
     is reachable for plain `docker`/`docker compose` (see item 4 below,
     which passed), but Testcontainers' bundled `docker-java` client
     requests API version 1.32, which this daemon's Compose/BuildKit stack
     rejects (`client version 1.32 is too old`). This is the same class of
     limitation flagged in Tasks 6–8 for this sandbox, not something
     introduced by this task. `MvpLifecycleIT` was verified for
     correctness by (a) full `test-compile` success and (b) code-level
     review against every collaborator it drives (`ExecutionService`,
     `HubPollingController`, `EvidenceService`, `TestPlanController`) to
     confirm field names, HTTP semantics, and SQL-level completion gating
     match exactly.
2. `npm --prefix frontend test -- --run && npm --prefix frontend run build && npm --prefix frontend run e2e`
   - Unit tests: 48/48 **pass**.
   - Build: **succeeds** (`tsc --noEmit && vite build`).
   - E2E (Playwright, real Chromium): 3/3 **pass**, including the new
     `mvp-lifecycle.spec.ts`. (Two pre-existing e2e specs needed a small,
     honest fix during this task — a `getByText` locator ambiguity in
     `generate-execute.spec.ts` and a Node version bump to run Vite 8 at
     all in this sandbox — neither changes application behavior.)
3. `python -m pytest contracts/tests local-hub/tests -q`
   - 59/60 **pass**, including the two new
     `local-hub/tests/integration/test_backend_contract.py` tests. One
     pre-existing test (`test_rendered_specs_typecheck_with_the_local_wdio_dependencies`)
     **cannot run**: it requires `MOBILE_SCRIPT_NODE_MODULES` pointing at a
     real, installed WebdriverIO `node_modules` tree under the
     intentionally git-ignored `mobile_script/` directory, which does not
     exist in this sandbox. This is an environment limitation, not a code
     defect — the same category as the Docker/Testcontainers gap above.
     (A separate, genuinely pre-existing bug — a `hub-envelope-v1.json`
     example missing the required `leaseToken` field in
     `contracts/tests/test_contract_examples.py` — was found and fixed as
     part of getting this suite to a true pass.)
4. `bash deploy/scripts/smoke.sh`
   - **PASSED** — ran against a freshly built-and-started
     `docker compose -f deploy/compose.yaml up -d --build` stack (all four
     services reported healthy): frontend served through the gateway, SPA
     deep-link fallback, backend readiness `UP` (Flyway + JPA schema
     validation), `/api/v1/operations` reachable, WebSocket route
     reachable and authenticated, and neither the backend nor Postgres
     port is published to the host. Stack was torn down
     (`docker compose down`) after the run.

## Step 3 — Physical Android acceptance path: NOT PERFORMED

This sandbox has **no physical Android device, no emulator, no Appium
server, and no Zalo test account**:

- `adb`: not installed / not on `PATH`.
- `appium`: not installed / not on `PATH`.
- `adb devices`: not runnable (no `adb` binary).

Per the honesty requirement for this task, this step was **not simulated or
fabricated**. What was verified instead, by code inspection, is that the
path this step would exercise is fully implemented and covered by tests
that use fakes/mocks in place of the real device/Appium/backend:

- `local-hub/src/opshub_hub/preflight.py` — device/Appium/Zalo checks,
  unit-tested in `local-hub/tests/test_preflight.py` with a fake command
  runner and prober.
- `local-hub/src/opshub_hub/runner.py` — the sequential five-cases-per-OA
  execution loop with the exact continuation/retry semantics this step
  calls for, unit-tested in `local-hub/tests/test_runner.py` and now also
  proven schema/field-contract-compatible end to end by
  `local-hub/tests/integration/test_backend_contract.py` (this task).
- The backend side of the same journey (offer → results → completion) is
  covered by `MvpLifecycleIT` (this task) and `HubProtocolIT`.

This step remains genuinely unexecuted against real hardware and must be
run manually before sign-off, exactly as Tasks 6–8 flagged for
Docker/Testcontainers-dependent coverage. See
`docs/operations/local-hub-runbook.md` sections 3–4 for the manual
procedure (adb/Appium/Zalo preflight and how to start Appium).

## Step 4 — Visual fidelity for all four screens: MANUAL/STRUCTURAL ONLY

No live-rendered pixel comparison against the PRD mockups was performed in
this session — that would require a real browser render at the primary
desktop viewport compared pixel-for-pixel against the mockup images, which
Tasks 10–11 already did and captured as baselines. This task:

- Confirmed the four baseline screenshots captured in Tasks 10–11 still
  exist and are referenced by the e2e suite:
  `frontend/e2e/visual-baselines/screen-1-input.png`,
  `screen-2-verify.png`, `screen-3-generate.png`, `screen-4-execute.png`.
- Did **not** recapture them (nothing in this task's changes touched
  screen layout, styling, the stepper, cards/tables, status treatments,
  typography, spacing, or action placement — only test files and docs
  changed).
- Confirmed structurally, by reading the four screen components exercised
  by the e2e specs in this task and Tasks 10–11
  (`InputScreen`/`VerifyScreen`, `GenerateScreen`, `ExecuteScreen`), that
  the sidebar is intentionally absent and the four-step stepper
  (`StepProgress`, `OPERATION_STEPS`) renders consistently across all four
  routes.

A full pixel-level re-comparison against the PRD images was out of scope
for this session's honest capability (no visual diff tooling was run) —
this is a structural/manual confirmation, not a re-verified pixel match.

## Final verification gate

- `git status --short` — reviewed; only Task 13 files (backend acceptance
  test, frontend e2e spec + a locator fix, local-hub integration test, a
  one-line contract-fixture fix, these three docs, and `README.md`) are
  changed/added. See the commit for the final list.
- `data/` and `mobile_script/` — confirmed still present in `.gitignore`
  and untracked (`git status` shows neither).
- No secret values are tracked — `deploy/env/backend.env` and
  `local-hub/.env` remain untracked/git-ignored; only variable *names*
  appear in this checklist and the runbook.
- The four visual baselines correspond to the supplied mockups — confirmed
  present at `frontend/e2e/visual-baselines/screen-{1..4}-*.png` and
  unchanged by this task; not recaptured (nothing in this task altered
  screen rendering).
