# OpsHub Android MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the four-screen OpsHub Android MVP from manual OA input through validation, controlled five-template generation, QC approval, MacBook execution, and persisted evidence.

**Architecture:** A React frontend and Spring Boot modular monolith run behind Nginx with PostgreSQL on the Rocky Linux VPS. A Python Local Hub on the MacBook connects primarily by WebSocket, falls back to HTTP long polling, renders tracked WebdriverIO templates, and runs them sequentially through Appium on one Android device.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Maven, PostgreSQL 16, Flyway, React 19, TypeScript, Vite, TanStack Query, Vitest, Playwright, Python 3.12, Pydantic 2, httpx, websockets, pytest, WebdriverIO 9, Appium 3, Nginx, Docker Compose.

## Global Constraints

- Android is the only platform shown and accepted by the MVP.
- The four images under `docs/PRD/` are the visual source of truth; the sidebar is the only intentional structural omission.
- The UI contains only Input OA Details, Verify Inputs, Generate and Review, and Confirm and Execute.
- Every OA generates exactly five fixed test cases in order.
- Gemini returns schema-validated findings and never generates executable code.
- Only `PASSED` field results satisfy generation gating.
- Every OA mutation or reorder increments the operation revision and invalidates downstream state.
- Execution continues through every test and OA after assertion failures.
- Assertion failures do not retry; infrastructure errors retry once.
- WebSocket is primary; HTTPS long polling is the automatic fallback.
- Screenshots and logs live on the filesystem; PostgreSQL stores metadata and relative paths.
- User authentication, Jira ingestion, additional platforms, parallel execution, and individual-case reruns are outside this plan.

---

## Delivery sequence

Tasks 1-6 establish the VPS-side contract and backend. Tasks 7-8 deliver the MacBook runner. Tasks 9-11 build the mockup-faithful UI. Tasks 12-13 integrate deployment and prove the acceptance path. Each task ends in an independently reviewable commit.

### Task 1: Repository foundation and shared contracts

**Files:**
- Create: `pom.xml`
- Create: `mvnw`
- Create: `mvnw.cmd`
- Create: `.mvn/wrapper/maven-wrapper.properties`
- Create: `backend/pom.xml`
- Create: `frontend/package.json`
- Create: `frontend/tsconfig.json`
- Create: `frontend/vite.config.ts`
- Create: `frontend/vitest.config.ts`
- Create: `frontend/playwright.config.ts`
- Create: `local-hub/pyproject.toml`
- Create: `contracts/openapi/opshub-v1.yaml`
- Create: `contracts/schemas/hub-envelope-v1.json`
- Create: `contracts/schemas/template-parameters-v1.json`
- Create: `deploy/compose.yaml`
- Create: `backend/.env.example`
- Create: `local-hub/.env.example`
- Modify: `.gitignore`
- Test: `contracts/tests/test_contract_examples.py`

**Interfaces:**
- Produces REST base path `/api/v1`, Hub endpoint `/ws/v1/hubs/{hubId}`, `HubEnvelopeV1`, and `TemplateParametersV1`.
- Produces canonical enums `FieldStatus`, `OperationStatus`, `TestCaseStatus`, `TestResultStatus`, and `ErrorCategory` in OpenAPI and JSON Schema.

- [ ] **Step 1: Write contract fixture tests**

Create fixtures for `JOB_OFFERED`, `JOB_PROGRESS`, `TEST_RESULT`, and `HEARTBEAT`. Assert each fixture validates against `hub-envelope-v1.json`, and assert a fixture missing `messageId` fails.

```python
def test_hub_envelope_requires_message_id(schema, validator):
    payload = {"version": 1, "type": "HEARTBEAT", "timestamp": "2026-07-26T00:00:00Z", "payload": {}}
    assert list(validator(schema).iter_errors(payload))
```

- [ ] **Step 2: Run the contract test and verify red**

Run: `python -m pytest contracts/tests/test_contract_examples.py -q`  
Expected: FAIL because schemas and fixtures do not exist.

- [ ] **Step 3: Define contracts and build manifests**

Define UUID identifiers, integer revisions, ISO-8601 timestamps, camelCase JSON, and explicit enum values. Configure Maven wrapper, frontend `test`, `build`, and `e2e` scripts, and Python development dependencies (`pytest`, `jsonschema`). Define template parameters as:

```json
{
  "oaName": "zBusiness",
  "thumbnailUrl": "https://example.test/thumb.png",
  "expectedHeader": "Header",
  "expectedBody": "Body",
  "expectedButtonText": "Open now",
  "expectedRedirectUrl": "https://example.test/path",
  "expectedRedirectDomain": "example.test"
}
```

Add `.env.example` names only. Add `data/`, secrets, dependencies, build output, screenshots, logs, and generated tests to `.gitignore`.

- [ ] **Step 4: Run contract and syntax verification**

Run: `python -m pytest contracts/tests/test_contract_examples.py -q`  
Expected: PASS.  
Run: `docker compose -f deploy/compose.yaml config -q`  
Expected: exit 0.

- [ ] **Step 5: Commit**

```bash
git add .gitignore pom.xml backend frontend/package.json local-hub contracts deploy
git commit -m "build: establish OpsHub contracts and project foundation"
```

### Task 2: Backend persistence, domain states, and revision invalidation

**Files:**
- Create: `backend/src/main/java/com/opshub/OpsHubApplication.java`
- Create: `backend/src/main/java/com/opshub/operation/domain/Operation.java`
- Create: `backend/src/main/java/com/opshub/operation/domain/OfficialAccount.java`
- Create: `backend/src/main/java/com/opshub/operation/domain/OperationStatus.java`
- Create: `backend/src/main/java/com/opshub/operation/application/OperationService.java`
- Create: `backend/src/main/java/com/opshub/operation/api/OperationController.java`
- Create: `backend/src/main/resources/db/migration/V1__initial_schema.sql`
- Create: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/opshub/operation/OperationServiceTest.java`
- Test: `backend/src/test/java/com/opshub/operation/OperationControllerIT.java`

**Interfaces:**
- Produces `OperationService.create(String jiraId)`, `replaceOas(UUID operationId, int expectedRevision, List<SaveOaCommand> oas)`, and `get(UUID id)`.
- Produces REST `POST /api/v1/operations`, `GET /api/v1/operations/{id}`, and `PUT /api/v1/operations/{id}/oas`.
- Emits `409 REVISION_CONFLICT` with `currentRevision` for stale writes.

- [ ] **Step 1: Write failing domain tests**

Test creation at revision 1, Android-only platform validation, ordered OA replacement, revision increment, and invalidation of validation/plan/approval references.

```java
assertThatThrownBy(() -> service.replaceOas(id, 1, editedOas))
    .isInstanceOf(RevisionConflictException.class);
```

- [ ] **Step 2: Verify the tests fail**

Run: `./mvnw -pl backend -Dtest=OperationServiceTest test`  
Expected: FAIL because the domain and service are absent.

- [ ] **Step 3: Implement schema and domain behavior**

Create normalized tables for operations, OAs, validation runs/findings, plans/cases, executions/results/evidence, hubs, and job leases. Use optimistic revision checks in one transaction. Store OA order as a unique `(operation_id, oa_order)` pair.

- [ ] **Step 4: Add API integration tests and implementation**

Use Testcontainers PostgreSQL. Assert create/get/update payloads, Android-only rejection, stale-write conflict, and persisted OA ordering.

- [ ] **Step 5: Run backend tests**

Run: `./mvnw -pl backend test`  
Expected: PASS with unit and PostgreSQL integration tests.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat: add operation persistence and revision lifecycle"
```

### Task 3: Deterministic validators and validation gating

**Files:**
- Create: `backend/src/main/java/com/opshub/validation/domain/FieldStatus.java`
- Create: `backend/src/main/java/com/opshub/validation/domain/FieldFinding.java`
- Create: `backend/src/main/java/com/opshub/validation/application/ContentParser.java`
- Create: `backend/src/main/java/com/opshub/validation/application/UrlDirectionValidator.java`
- Create: `backend/src/main/java/com/opshub/validation/application/ThumbnailValidator.java`
- Create: `backend/src/main/java/com/opshub/validation/application/ValidationService.java`
- Create: `backend/src/main/java/com/opshub/validation/api/ValidationController.java`
- Test: `backend/src/test/java/com/opshub/validation/ContentParserTest.java`
- Test: `backend/src/test/java/com/opshub/validation/UrlDirectionValidatorTest.java`
- Test: `backend/src/test/java/com/opshub/validation/ThumbnailValidatorTest.java`
- Test: `backend/src/test/java/com/opshub/validation/ValidationGatingIT.java`

**Interfaces:**
- Produces `ParsedContent ContentParser.parse(String content)` with `header` and `body`.
- Produces `FieldFinding validate(String rawValue)` for URL and thumbnail validators.
- Produces `ValidationRunDto ValidationService.validate(UUID operationId, int revision)`.

- [ ] **Step 1: Write validator edge-case tests**

Cover absent body, preserved multiline body, raw whitespace, `stg-` prefix, malformed URL, redirect limit, non-image response, oversized payload, decode failure, timeout, and successful PNG.

- [ ] **Step 2: Verify red**

Run: `./mvnw -pl backend -Dtest='ContentParserTest,UrlDirectionValidatorTest,ThumbnailValidatorTest' test`  
Expected: FAIL because validators do not exist.

- [ ] **Step 3: Implement minimal deterministic validators**

Use Java `URI`, an HTTP client with bounded connect/read timeouts, at most five redirects, a configured byte limit, MIME verification, and `ImageIO.read`. Return `FAILED` for invalid data and `UNABLE_TO_CHECK` for network/dependency failures.

- [ ] **Step 4: Implement validation orchestration and gating**

Persist findings against operation revision. Mark the run passed only when every deterministic and later LLM finding is `PASSED`. Return disabled reasons from the API rather than recomputing them in React.

- [ ] **Step 5: Run tests**

Run: `./mvnw -pl backend test`  
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat: add deterministic input validation and gating"
```

### Task 4: Gemini text validation adapter

**Files:**
- Create: `backend/src/main/java/com/opshub/validation/llm/TextValidationPort.java`
- Create: `backend/src/main/java/com/opshub/validation/llm/GeminiTextValidationAdapter.java`
- Create: `backend/src/main/java/com/opshub/validation/llm/GeminiResponse.java`
- Create: `backend/src/main/java/com/opshub/validation/llm/TextValidationPrompt.java`
- Modify: `backend/src/main/java/com/opshub/validation/application/ValidationService.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/opshub/validation/llm/GeminiTextValidationAdapterTest.java`
- Test: `backend/src/test/java/com/opshub/validation/ValidationServiceTest.java`

**Interfaces:**
- Produces `List<FieldFinding> TextValidationPort.validate(TextValidationRequest request)`.
- Consumes `GEMINI_API_KEY` and `GEMINI_MODEL`, defaulting the model to `gemini-2.5-flash-lite`.

- [ ] **Step 1: Write adapter contract tests**

Stub Gemini HTTP responses for passed content, spelling failure with location, misleading warning, low confidence, malformed JSON, 429, and timeout. Assert malformed/dependency cases map to `UNABLE_TO_CHECK`.

- [ ] **Step 2: Verify red**

Run: `./mvnw -pl backend -Dtest='GeminiTextValidationAdapterTest,ValidationServiceTest' test`  
Expected: FAIL because the port and adapter are absent.

- [ ] **Step 3: Implement the constrained request and response schema**

Require one finding per requested field with `status`, `message`, `start`, `end`, `suggestion`, `severity`, `confidence`, and `policyVersion`. Reject unknown status values, missing fields, out-of-range confidence, and invalid offsets.

- [ ] **Step 4: Integrate content and button validation**

Send only content/header/body and button text. Merge Gemini findings with deterministic findings. Never promote a deterministic failure. Persist model and policy version with the run.

- [ ] **Step 5: Run tests without a real key**

Run: `./mvnw -pl backend test`  
Expected: PASS using stubbed HTTP only.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat: add schema-constrained Gemini validation"
```

### Task 5: Versioned template catalog and five-case generation

**Files:**
- Create: `backend/src/main/java/com/opshub/generation/domain/TemplateId.java`
- Create: `backend/src/main/java/com/opshub/generation/application/TestPlanService.java`
- Create: `backend/src/main/java/com/opshub/generation/api/TestPlanController.java`
- Create: `local-hub/templates/android/manifest.json`
- Create: `local-hub/templates/android/tests/android-oa-delivery-v1.spec.ts.hbs`
- Create: `local-hub/templates/android/tests/android-thumbnail-v1.spec.ts.hbs`
- Create: `local-hub/templates/android/tests/android-content-v1.spec.ts.hbs`
- Create: `local-hub/templates/android/tests/android-button-text-v1.spec.ts.hbs`
- Create: `local-hub/templates/android/tests/android-redirect-v1.spec.ts.hbs`
- Create: `local-hub/templates/android/pages/*.ts`
- Test: `backend/src/test/java/com/opshub/generation/TestPlanServiceTest.java`
- Test: `local-hub/tests/templates/test_template_catalog.py`

**Interfaces:**
- Produces `TestPlanDto generate(UUID operationId, int revision)` and `approve(UUID planId, int revision)`.
- Produces exactly five cases with orders 1-5 and the template IDs defined in the design.
- Produces template manifest entries `{id, version, sha256, parameterSchema}`.

- [ ] **Step 1: Copy and sanitize the five reference tests**

Move no dependency artifacts. Parameterize only OA name, thumbnail URL, header/body, button text, redirect URL, and redirect domain. Keep selectors and execution logic fixed.

- [ ] **Step 2: Write failing generation and rendering tests**

Assert generation is blocked for non-passed validation, produces five cases in fixed order, records the current revision, and invalidates after input mutation. Render Vietnamese text containing quotes, backticks, newlines, emoji, and query strings.

- [ ] **Step 3: Verify red**

Run: `./mvnw -pl backend -Dtest=TestPlanServiceTest test`  
Expected: FAIL.  
Run: `python -m pytest local-hub/tests/templates/test_template_catalog.py -q`  
Expected: FAIL.

- [ ] **Step 4: Implement deterministic generation**

Build typed parameters from validated OA values, compute redirect hostname with `URI`, store template version and checksum, and prevent approval until every case is ready.

- [ ] **Step 5: Verify generated TypeScript**

Run: `python -m pytest local-hub/tests/templates/test_template_catalog.py -q`  
Expected: PASS and every rendered spec passes `npx tsc --noEmit` in a temporary work directory.

- [ ] **Step 6: Run backend generation tests**

Run: `./mvnw -pl backend test`  
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend local-hub/templates local-hub/tests
git commit -m "feat: generate five controlled Android test cases"
```

### Task 6: Execution queue, Hub protocol, leases, and evidence APIs

**Files:**
- Create: `backend/src/main/java/com/opshub/execution/application/ExecutionService.java`
- Create: `backend/src/main/java/com/opshub/execution/application/LeaseService.java`
- Create: `backend/src/main/java/com/opshub/execution/api/ExecutionController.java`
- Create: `backend/src/main/java/com/opshub/hub/api/HubWebSocketHandler.java`
- Create: `backend/src/main/java/com/opshub/hub/api/HubPollingController.java`
- Create: `backend/src/main/java/com/opshub/evidence/application/EvidenceService.java`
- Create: `backend/src/main/java/com/opshub/evidence/api/EvidenceController.java`
- Test: `backend/src/test/java/com/opshub/execution/ExecutionServiceTest.java`
- Test: `backend/src/test/java/com/opshub/hub/HubProtocolIT.java`
- Test: `backend/src/test/java/com/opshub/evidence/EvidenceServiceTest.java`

**Interfaces:**
- Produces `POST /api/v1/operations/{id}/executions`.
- Produces WebSocket `/ws/v1/hubs/{hubId}` and fallback `GET /api/v1/hubs/{hubId}/jobs/next?waitSeconds=25`.
- Produces heartbeat, lease renewal, progress, result, and multipart evidence endpoints.
- Uses the same `HubEnvelopeV1` payloads for WebSocket and polling.

- [ ] **Step 1: Write state-machine and idempotency tests**

Assert current approval and online Hub are required, duplicate idempotency keys return the original execution, one active job is leased per Hub, heartbeat renews the lease, and expired jobs can be reoffered without duplicating stored results.

- [ ] **Step 2: Verify red**

Run: `./mvnw -pl backend -Dtest='ExecutionServiceTest,HubProtocolIT,EvidenceServiceTest' test`  
Expected: FAIL.

- [ ] **Step 3: Implement queue and transport handlers**

Use database-backed leases with `lease_id`, `expires_at`, and compare-and-set renewal. Validate the Hub token with constant-time comparison. Enforce monotonic message IDs per execution and idempotent result upserts.

- [ ] **Step 4: Implement evidence persistence**

Store files below the configured evidence root using generated UUID names, never client paths. Verify declared size and SHA-256, then persist metadata and relative path. Reject traversal, unsupported type, and oversized files.

- [ ] **Step 5: Run backend tests**

Run: `./mvnw -pl backend test`  
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend contracts
git commit -m "feat: add execution queue and resilient Hub protocol"
```

### Task 7: Python Local Hub transport, journal, and outbox

**Files:**
- Create: `local-hub/src/opshub_hub/config.py`
- Create: `local-hub/src/opshub_hub/models.py`
- Create: `local-hub/src/opshub_hub/transport/websocket_client.py`
- Create: `local-hub/src/opshub_hub/transport/polling_client.py`
- Create: `local-hub/src/opshub_hub/transport/failover.py`
- Create: `local-hub/src/opshub_hub/journal.py`
- Create: `local-hub/src/opshub_hub/outbox.py`
- Test: `local-hub/tests/test_transport_failover.py`
- Test: `local-hub/tests/test_journal.py`
- Test: `local-hub/tests/test_outbox.py`

**Interfaces:**
- Produces `FailoverTransport.receive_job()`, `send(envelope)`, and `heartbeat()`.
- Produces `ExecutionJournal.claim(execution_id, idempotency_key)` and `complete(execution_id)`.
- Produces `Outbox.enqueue(envelope)` and `flush(transport)`.

- [ ] **Step 1: Write failover tests with fake clocks and transports**

Assert three consecutive WebSocket failures activate polling, successful probes return to WebSocket, duplicate job claims are rejected, and queued results preserve order across restart.

- [ ] **Step 2: Verify red**

Run: `python -m pytest local-hub/tests/test_transport_failover.py local-hub/tests/test_journal.py local-hub/tests/test_outbox.py -q`  
Expected: FAIL.

- [ ] **Step 3: Implement typed configuration and protocol models**

Require backend URL, Hub ID, Hub token, template root, and data root. Parse all envelopes with Pydantic using forbidden extra fields and exact enum values.

- [ ] **Step 4: Implement failover and durable SQLite state**

Use exponential reconnect delay with a cap, 25-second long polling, periodic WebSocket probes, a SQLite journal, and a FIFO outbox. Never delete an outbox event before server acknowledgement.

- [ ] **Step 5: Run Hub tests**

Run: `python -m pytest local-hub/tests -q`  
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add local-hub
git commit -m "feat: add resilient Local Hub transport and journal"
```

### Task 8: Local runner, Appium preflight, retries, and evidence

**Files:**
- Create: `local-hub/src/opshub_hub/preflight.py`
- Create: `local-hub/src/opshub_hub/templates.py`
- Create: `local-hub/src/opshub_hub/runner.py`
- Create: `local-hub/src/opshub_hub/classification.py`
- Create: `local-hub/src/opshub_hub/evidence.py`
- Create: `local-hub/src/opshub_hub/main.py`
- Test: `local-hub/tests/test_preflight.py`
- Test: `local-hub/tests/test_runner.py`
- Test: `local-hub/tests/test_classification.py`

**Interfaces:**
- Produces `PreflightReport run_preflight()`.
- Produces `ExecutionSummary Runner.run(Job job)`.
- Consumes the five-template manifest and emits progress/result/evidence envelopes.

- [ ] **Step 1: Write runner tests with a fake subprocess**

Assert fixed OA/test ordering, continuation after assertion failure, one retry after infrastructure error, no retry after assertion failure, per-attempt logs, final evidence capture, and outbox behavior when upload fails.

- [ ] **Step 2: Verify red**

Run: `python -m pytest local-hub/tests/test_preflight.py local-hub/tests/test_runner.py local-hub/tests/test_classification.py -q`  
Expected: FAIL.

- [ ] **Step 3: Implement preflight and safe materialization**

Check executable versions, ADB device state, Appium reachability, Zalo package installation, manifest checksum, and writable data directories. Render only declared parameters into a fresh execution directory.

- [ ] **Step 4: Implement sequential subprocess execution**

Launch one spec per subprocess with bounded timeout. Classify Mocha assertion output as `ASSERTION` and Appium/session/device/network failures as `INFRASTRUCTURE`. Reset Appium session before the single infrastructure retry.

- [ ] **Step 5: Implement evidence and result reporting**

Capture the final screen on pass and the failure screen on fail/error. Compute SHA-256, upload separately, and preserve local files until acknowledged.

- [ ] **Step 6: Run all Hub tests**

Run: `python -m pytest local-hub/tests -q`  
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add local-hub
git commit -m "feat: execute Android templates through the Local Hub"
```

### Task 9: Frontend foundation and mockup-derived visual system

**Files:**
- Create: `frontend/src/main.tsx`
- Create: `frontend/src/app/App.tsx`
- Create: `frontend/src/app/router.tsx`
- Create: `frontend/src/api/client.ts`
- Create: `frontend/src/api/generated.ts`
- Create: `frontend/src/styles/tokens.css`
- Create: `frontend/src/styles/global.css`
- Create: `frontend/src/components/StepProgress.tsx`
- Create: `frontend/src/components/StatusBadge.tsx`
- Create: `frontend/src/components/Card.tsx`
- Create: `frontend/src/components/DisabledReason.tsx`
- Test: `frontend/src/components/StepProgress.test.tsx`
- Test: `frontend/src/components/StatusBadge.test.tsx`

**Interfaces:**
- Produces routes `/operations/:operationId/input`, `/verify`, `/generate`, and `/execute`.
- Produces reusable mockup-derived primitives and canonical status-to-color mapping.

- [ ] **Step 1: Extract visual tokens from the four mockups**

Record desktop content width, stepper dimensions, blue/green/orange/red/gray palette, border radii, shadows, typography scale, row heights, and spacing values in CSS custom properties. Do not add a sidebar.

- [ ] **Step 2: Write component behavior tests**

Assert step completion/current state, accessible status names, status color classes, keyboard focus, and visible disabled reasons.

- [ ] **Step 3: Verify red**

Run: `npm --prefix frontend test -- --run`  
Expected: FAIL because components are absent.

- [ ] **Step 4: Implement the shell and visual primitives**

Use semantic HTML, visible focus, minimum 44-pixel interactive targets, and desktop-first responsive CSS. Keep frontend state server-derived through TanStack Query.

- [ ] **Step 5: Run frontend tests and build**

Run: `npm --prefix frontend test -- --run`  
Expected: PASS.  
Run: `npm --prefix frontend run build`  
Expected: exit 0.

- [ ] **Step 6: Commit**

```bash
git add frontend
git commit -m "feat: establish mockup-derived frontend system"
```

### Task 10: Screens 1 and 2 — OA input and validation

**Files:**
- Create: `frontend/src/features/operations/InputScreen.tsx`
- Create: `frontend/src/features/operations/OaEditor.tsx`
- Create: `frontend/src/features/operations/ContentPreview.tsx`
- Create: `frontend/src/features/validation/VerifyScreen.tsx`
- Create: `frontend/src/features/validation/ValidationFinding.tsx`
- Create: `frontend/src/features/operations/useOperation.ts`
- Test: `frontend/src/features/operations/InputScreen.test.tsx`
- Test: `frontend/src/features/validation/VerifyScreen.test.tsx`
- Test: `frontend/e2e/input-validation.spec.ts`

**Interfaces:**
- Consumes operation and validation REST APIs.
- Produces current-revision navigation to Screen 3 only when `canGenerate=true`.

- [ ] **Step 1: Write screen behavior tests**

Cover add/remove/reorder, Android-only display, required-field gating, header/body preview, optimistic revision conflict, issue-to-field navigation, re-check, and disabled-reason text.

- [ ] **Step 2: Verify red**

Run: `npm --prefix frontend test -- --run InputScreen VerifyScreen`  
Expected: FAIL.

- [ ] **Step 3: Implement Screen 1 from mockup 1**

Match its stepper, OA tabs/cards, numbered labels, input proportions, overview panel content, and primary action placement after removing the sidebar. Preserve multiline content exactly.

- [ ] **Step 4: Implement Screen 2 from mockup 2**

Match its issue banner, OA summary table, issue panel, status badges, re-check action, and disabled Generate treatment. Add field-level detail required by the PRD without disturbing the mockup hierarchy.

- [ ] **Step 5: Run tests and Playwright flow**

Run: `npm --prefix frontend test -- --run`  
Expected: PASS.  
Run: `npm --prefix frontend run e2e -- input-validation.spec.ts`  
Expected: PASS.

- [ ] **Step 6: Capture desktop comparison screenshots**

Capture Screens 1 and 2 at the mockup aspect ratio. Compare stepper, panel geometry, table/card hierarchy, status treatments, typography, spacing, and action placement. Store approved baselines under `frontend/e2e/visual-baselines/`.

- [ ] **Step 7: Commit**

```bash
git add frontend
git commit -m "feat: add mockup-faithful OA input and validation screens"
```

### Task 11: Screens 3 and 4 — review, execution, and live updates

**Files:**
- Create: `frontend/src/features/generation/GenerateScreen.tsx`
- Create: `frontend/src/features/generation/TestCaseRow.tsx`
- Create: `frontend/src/features/execution/ExecuteScreen.tsx`
- Create: `frontend/src/features/execution/ExecutionQueue.tsx`
- Create: `frontend/src/features/execution/ExecutionLog.tsx`
- Create: `frontend/src/realtime/useExecutionChannel.ts`
- Test: `frontend/src/features/generation/GenerateScreen.test.tsx`
- Test: `frontend/src/features/execution/ExecuteScreen.test.tsx`
- Test: `frontend/src/realtime/useExecutionChannel.test.ts`
- Test: `frontend/e2e/generate-execute.spec.ts`

**Interfaces:**
- Consumes plan generation/approval, execution, evidence, WebSocket, and REST refresh interfaces.
- Produces full-plan start and current execution rendering; no individual rerun endpoint is consumed.

- [ ] **Step 1: Write screen and realtime tests**

Assert exactly five cases per OA, fixed ordering, expandable script preview, readiness gating, approval, Hub/device readiness gating, queue ordering, retries, evidence links, final summary, WebSocket updates, and REST refresh after disconnect.

- [ ] **Step 2: Verify red**

Run: `npm --prefix frontend test -- --run GenerateScreen ExecuteScreen useExecutionChannel`  
Expected: FAIL.

- [ ] **Step 3: Implement Screen 3 from mockup 3**

Match expandable OA cards, five-row table, group/status treatments, action area, and Confirm placement. Omit mockup add/delete buttons because the approved catalog is fixed; provide read-only script expansion instead.

- [ ] **Step 4: Implement Screen 4 from mockup 4**

Match the left execution queue, current-run panel, progress bar, test table, tabs/log table, and Start placement. Add evidence links and final aggregate state within the same hierarchy.

- [ ] **Step 5: Implement realtime fallback**

Open WebSocket for live events. On closure, invalidate execution queries every three seconds. Stop polling after WebSocket reconnection. Deduplicate events by message ID.

- [ ] **Step 6: Run tests, E2E, and visual comparisons**

Run: `npm --prefix frontend test -- --run`  
Expected: PASS.  
Run: `npm --prefix frontend run e2e -- generate-execute.spec.ts`  
Expected: PASS.  
Capture Screens 3 and 4 at the mockup aspect ratio and approve baselines only after hierarchy, spacing, status colors, and queue layout match.

- [ ] **Step 7: Commit**

```bash
git add frontend
git commit -m "feat: add mockup-faithful generation and execution screens"
```

### Task 12: Gateway, containers, and Rocky Linux deployment

**Files:**
- Create: `backend/Dockerfile`
- Create: `frontend/Dockerfile`
- Create: `gateway/nginx.conf`
- Create: `gateway/conf.d/opshub.conf`
- Modify: `deploy/compose.yaml`
- Create: `deploy/env/backend.env.example`
- Create: `deploy/scripts/preflight.sh`
- Create: `deploy/scripts/smoke.sh`
- Create: `docs/deployment/rocky-linux-9.md`
- Test: `deploy/tests/nginx-routing.sh`

**Interfaces:**
- Exposes only HTTPS/HTTP through Nginx.
- Routes `/api/` and `/ws/` to backend and all application routes to React.
- Mounts `/opt/opshub/data` and `/opt/opshub/postgres` persistently.

- [ ] **Step 1: Write gateway routing checks**

Assert frontend history fallback, API proxying, WebSocket upgrade headers, evidence upload limit, and absence of public backend/database ports.

- [ ] **Step 2: Verify red**

Run: `bash deploy/tests/nginx-routing.sh`  
Expected: FAIL because gateway configuration is absent.

- [ ] **Step 3: Implement production images and Compose services**

Use multi-stage builds, non-root application users, health checks, restart policies, named/private networks, and external secret env files. Configure Flyway to migrate before readiness becomes healthy.

- [ ] **Step 4: Implement Rocky Linux preflight and documentation**

Check Docker/Compose, ports, directories, permissions, secret file presence by variable name only, disk space, and SELinux-compatible volume labels. Document TLS certificate placement and firewall ports.

- [ ] **Step 5: Build and smoke test**

Run: `docker compose -f deploy/compose.yaml build`  
Expected: exit 0.  
Run: `docker compose -f deploy/compose.yaml up -d`  
Expected: all health checks become healthy.  
Run: `bash deploy/scripts/smoke.sh`  
Expected: frontend, backend readiness, database migration, REST proxy, and WebSocket upgrade pass.

- [ ] **Step 6: Commit**

```bash
git add backend/Dockerfile frontend/Dockerfile gateway deploy docs/deployment
git commit -m "ops: add Rocky Linux container deployment"
```

### Task 13: End-to-end MVP acceptance and handoff

**Files:**
- Create: `backend/src/test/java/com/opshub/acceptance/MvpLifecycleIT.java`
- Create: `frontend/e2e/mvp-lifecycle.spec.ts`
- Create: `local-hub/tests/integration/test_backend_contract.py`
- Create: `docs/acceptance/android-mvp-checklist.md`
- Create: `docs/operations/local-hub-runbook.md`
- Modify: `README.md`

**Interfaces:**
- Exercises every contract produced by Tasks 1-12.
- Produces operator commands and an evidence-backed acceptance record.

- [ ] **Step 1: Write automated lifecycle acceptance tests**

Cover multiple OAs, validation failure and correction, five-case generation, approval, stale revision rejection, Hub readiness, sequential execution, continuation after failure, one infrastructure retry, evidence upload, WebSocket events, and polling fallback.

- [ ] **Step 2: Run full automated verification**

Run: `./mvnw -pl backend test`  
Expected: PASS.  
Run: `npm --prefix frontend test -- --run && npm --prefix frontend run build && npm --prefix frontend run e2e`  
Expected: PASS.  
Run: `python -m pytest contracts/tests local-hub/tests -q`  
Expected: PASS.  
Run: `bash deploy/scripts/smoke.sh`  
Expected: PASS.

- [ ] **Step 3: Run the physical Android acceptance path**

Connect the configured Android device, confirm Zalo login and campaign preconditions, start Appium and the Hub, then execute one operation containing at least two OAs. Record execution ID, final statuses, retry behavior, evidence paths, and observed transport.

- [ ] **Step 4: Verify visual fidelity for all four screens**

At the primary desktop viewport, compare each rendered screen with its matching PRD image. Accept only after the stepper, information hierarchy, cards/tables, status treatments, typography scale, spacing rhythm, and action placement match, with the sidebar intentionally absent.

- [ ] **Step 5: Complete the runbooks**

Document first startup, environment variable names, Hub/device preflight, Appium startup, transport status, evidence cleanup, database backup, service restart, and common recovery commands. Include no secret values.

- [ ] **Step 6: Commit**

```bash
git add backend frontend local-hub docs README.md
git commit -m "test: verify the OpsHub Android MVP lifecycle"
```

## Final verification gate

Before declaring the MVP complete, run all commands from Task 13 on the final commit and retain their outputs. Confirm `git status --short` contains no unintended files, `data/` and `mobile_script/` remain ignored, no secret values are tracked, and the four visual baselines correspond to the supplied mockups.
