# OpsHub Android MVP Handoff

**Date:** 2026-07-27  
**Branch:** `feature/opshub-android-mvp`  
**Worktree:** `/Users/sol/Projects/zah-26-v2/.worktrees/opshub-android-mvp`  
**Implementation status:** Tasks 1-5 complete; resume at Task 6

## Source documents

- Design: `docs/superpowers/specs/2026-07-26-opshub-android-mvp-design.md`
- Implementation plan: `docs/superpowers/plans/2026-07-26-opshub-android-mvp.md`
- PRD: `/Users/sol/Projects/zah-26-v2/docs/PRD/12438cc4-4794-4d82-865f-43f40ce63fc1_Automation_Testing_-_OpsHub_-_PRD.pdf`

The four supplied mockups are the frontend visual source of truth. They remain in the main checkout:

1. Input OA Details: `/Users/sol/Projects/zah-26-v2/docs/PRD/1785006091519_5216597871328133526_g2341689255617622924_ae325732c626fe83e72abbf9a38e0ab5.jpg`
2. Verify Inputs: `/Users/sol/Projects/zah-26-v2/docs/PRD/1785006123329_5216597871328133526_g2341689255617622924_c6ecfb96df67752e243b85e852b7443f.jpg`
3. Generate Test Cases: `/Users/sol/Projects/zah-26-v2/docs/PRD/1785006136884_5216597871328133526_g2341689255617622924_0bd7f670107a5339b7295f0fd97df4a2.jpg`
4. Confirm and Execute: `/Users/sol/Projects/zah-26-v2/docs/PRD/1785006560948_5216597871328133526_g2341689255617622924_421a4b16712a712779fcb809ec3c2292.jpg`

The sidebar shown in those images is intentionally omitted. All other hierarchy, stepper, cards, tables, status colors, typography scale, spacing, and action placement should follow the mockups.

## Completed work

### Task 1: Foundation and contracts

- Maven, frontend, Python, and Compose project foundations.
- OpenAPI REST contract and strict Hub/template JSON Schemas.
- Exactly five fixed Android template IDs encoded in Hub contracts.
- Executable Python contract suite.

Verification: 10 contract tests pass.

### Task 2: Backend operation lifecycle

- PostgreSQL/Flyway initial schema.
- Operation and ordered OA persistence.
- Android-only enforcement.
- Optimistic revision conflicts and downstream invalidation.
- REST create/get/replace-OA endpoints aligned with OpenAPI.

Java compilation passes. Docker-backed Testcontainers tests require a running Docker daemon.

### Task 3: Deterministic validation

- Content header/body parsing.
- Safe HTTP(S) and Android deeplink validation.
- Thumbnail redirect, MIME, size, timeout, and image decoding checks.
- Configurable validation limits.
- Revision-bound field findings and all-passed gating.

Verification: 23 focused tests pass.

### Task 4: Gemini validation

- `TextValidationPort` and Gemini adapter.
- Strict provider and local response schema validation.
- Content/button-only payloads.
- Timeout, rate limit, malformed response, and dependency errors map to `UNABLE_TO_CHECK`.
- Deterministic failures cannot be promoted by Gemini.
- Default model: `gemini-2.5-flash-lite`.

Verification: 15 focused tests pass. No real Gemini key was used.

### Task 5: Five-template generation

- Five tracked sanitized Android templates and required page objects.
- Versioned manifest, entry checksums, parameter schema, and catalog-version validation.
- Exactly five fixed ordered cases per OA.
- Revision/all-passed gating and QC approval.
- Production readiness validator renders JSON-safe values and invokes configured Node/TypeScript with a bounded timeout before marking cases ready.

Verification:

- Java generation/readiness: 7 tests pass.
- Python template catalog and all five rendered TypeScript specs: 3 tests pass.
- Contract baseline: 10 tests pass.

## Important runtime configuration

Backend Gemini variables:

```text
GEMINI_API_KEY=<secret>
GEMINI_MODEL=gemini-2.5-flash-lite
```

Template readiness variables must point to packaged runtime assets:

```text
OPSHUB_TEMPLATES_ROOT=<path-to-tracked-android-template-catalog>
OPSHUB_NODE_EXECUTABLE=<node-executable>
OPSHUB_TSC_ENTRY=<typescript-bin-tsc>
OPSHUB_TEMPLATE_COMPILE_TIMEOUT=<duration>
OPSHUB_TEMPLATE_CATALOG_VERSION=android-v1
```

Task 12 must copy/mount `local-hub/templates/android`, package pinned Node/TypeScript tooling in the backend image (or provide an equivalent controlled service), and set these variables. Without that wiring, production generation correctly remains not ready.

Local verification used bundled Node 24 and ignored dependencies under the original `mobile_script/` directory. System Node 18 is too old for the installed Appium/WebdriverIO packages.

## Remaining plan

Resume with these sections in the implementation plan:

6. Execution queue, Hub protocol, leases, and evidence APIs.
7. Python Local Hub WebSocket/polling failover, journal, and outbox.
8. Appium runner, preflight, retries, and evidence.
9. Frontend shell and mockup-derived visual system.
10. Mockup screens 1 and 2.
11. Mockup screens 3 and 4 with live updates.
12. Nginx, Docker images, Compose, Rocky Linux deployment, and template compiler wiring.
13. Full automated/physical Android acceptance and handoff.

## Resume commands

```bash
cd /Users/sol/Projects/zah-26-v2/.worktrees/opshub-android-mvp
git status --short
git log --oneline --decorate -15
```

Use the Task 6 section from `docs/superpowers/plans/2026-07-26-opshub-android-mvp.md`. The SDD ledger is stored in the ignored workspace:

```text
.superpowers/sdd/2026-07-26-opshub-android-mvp/progress.md
```

## Known environment limitations

- Docker Desktop was not running, so PostgreSQL/Testcontainers integration suites were not executed.
- The physical Android/Appium acceptance run belongs to Task 13.
- No production Gemini call was made; add the key only through ignored/local or VPS secret environment files.
- Frontend implementation has not started; mockup fidelity remains a required acceptance gate.

