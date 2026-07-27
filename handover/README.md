# OpsHub Android MVP Handover Index

This folder is the entry point for continuing the OpsHub Android MVP.

## Current state

- Branch at handoff: `feature/opshub-android-mvp`
- Completed and reviewed: implementation-plan Tasks 1-5
- Resume from: Task 6
- Detailed status: [implementation handoff](../docs/handover/2026-07-27-android-mvp-handoff.md)

## Product and implementation documents

- [Original PRD PDF](prd/OpsHub-PRD.pdf)
- [Approved MVP design](../docs/superpowers/specs/2026-07-26-opshub-android-mvp-design.md)
- [Complete implementation plan](../docs/superpowers/plans/2026-07-26-opshub-android-mvp.md)
- [Detailed implementation handoff](../docs/handover/2026-07-27-android-mvp-handoff.md)

## UI mockups

The mockups are the frontend visual source of truth. The sidebar is intentionally omitted in the MVP; preserve the remaining information hierarchy, four-step progress indicator, cards, tables, status colors, typography, spacing, and actions.

1. [Input OA Details](mockups/01-input-oa-details.jpg)
2. [Verify Inputs](mockups/02-verify-inputs.jpg)
3. [Generate Test Cases](mockups/03-generate-test-cases.jpg)
4. [Confirm and Execute](mockups/04-confirm-execute.jpg)

## API and protocol specifications

- [REST OpenAPI specification](../contracts/openapi/opshub-v1.yaml)
- [Hub WebSocket/HTTP envelope schema](../contracts/schemas/hub-envelope-v1.json)
- [Template parameter schema](../contracts/schemas/template-parameters-v1.json)
- [Executable contract examples](../contracts/tests/test_contract_examples.py)

## Implemented source locations

- Backend operation lifecycle: [`backend/src/main/java/com/opshub/operation/`](../backend/src/main/java/com/opshub/operation/)
- Deterministic and Gemini validation: [`backend/src/main/java/com/opshub/validation/`](../backend/src/main/java/com/opshub/validation/)
- Test-plan generation: [`backend/src/main/java/com/opshub/generation/`](../backend/src/main/java/com/opshub/generation/)
- Android template catalog: [`local-hub/templates/android/`](../local-hub/templates/android/)
- Database migrations: [`backend/src/main/resources/db/migration/`](../backend/src/main/resources/db/migration/)

## Verification recorded at handoff

- Contract suite: 10 passing tests.
- Deterministic validation: 23 passing focused tests.
- Gemini adapter: 15 passing focused tests.
- Generation/readiness: 7 passing Java tests.
- Template rendering and TypeScript compilation: 3 passing tests covering all five templates.
- Docker-backed PostgreSQL/Testcontainers suites were not run because Docker Desktop was unavailable.

## Remaining work

Follow Tasks 6-13 in the [implementation plan](../docs/superpowers/plans/2026-07-26-opshub-android-mvp.md): execution queue, Local Hub transport, Appium runner, four-screen frontend, VPS deployment, and end-to-end acceptance.

