# Evidence Viewer Design

## Problem

Evidence (screenshots, logs) uploaded by the Local Hub for failed test cases
is stored correctly on the backend (filesystem + `evidence` table), but there
is no way to view it: no read endpoint exists on the backend, and the
Execute screen's "View Evidence" button is hardcoded `disabled` with a
tooltip saying it isn't available yet.

## Goal

Let a user click "View Evidence" on a failed test case row and see the
captured screenshot(s)/log(s) in an in-app modal. View-only — no delete,
re-upload, or pagination.

## Backend

Two new `@GetMapping` endpoints on the existing `EvidenceController`
(`/api/v1/test-results/{testResultId}/evidence`), unauthenticated like every
other browser-facing endpoint (`operations`, `plans`, `executions`) — only
Hub-facing writes require `X-Hub-Token`.

1. `GET /api/v1/test-results/{testResultId}/evidence`
   List evidence metadata for a test result: `id`, `evidenceType`,
   `sizeBytes`, `checksum`, `createdAt`. Returns `[]` if none exist (not a
   404 — "no evidence yet" is a normal state, e.g. upload still in flight).
   A test result can have more than one evidence row (multiple screenshots,
   or a screenshot + a log), so this is always a list.

2. `GET /api/v1/evidence/{evidenceId}/content`
   Streams the raw file bytes with `Content-Type` set from `evidenceType`
   (`image/png` or `image/jpeg` for `SCREENSHOT` based on the stored file's
   extension, `text/plain` for `LOG`). 404 if the DB row or the underlying
   file is missing (the file being missing after passing preflight checks
   would itself be a data-integrity bug worth surfacing, not silently
   swallowing to an empty response).

`EvidenceService` gains `listForTestResult(testResultId)` and
`loadContent(evidenceId)`, both plain reads next to the existing
`store()`/`requireTestResultExists()`.

## Frontend

- `apiClient.listEvidence(testResultId)` — new client method, `GET` to the
  list endpoint above.
- Evidence content is loaded via a direct `<img src>` / `fetch` to
  `/api/v1/evidence/{id}/content` — no dedicated client wrapper needed for
  binary content.
- New `EvidenceModal` component (`frontend/src/features/execution/`):
  opens on demand, fetches the list for a given `testResultId`, renders each
  item — images inline via `<img>`, logs fetched as text into a `<pre>`
  block. Shows "No evidence available" when the list is empty rather than a
  blank modal.
- `ExecuteScreen.tsx`: the "View Evidence" button (currently always
  `disabled`, shown only for failed rows) becomes real. The real
  `test_results.id` needed to call the new endpoints is already present in
  `execution.results` (fetched via the existing `GET /executions/{id}`
  call, `ExecutionTestResult.id`) — matched by `testCaseId`, picking the
  entry with the highest `attempt` if more than one. No changes needed to
  `executionState.ts`'s WS/poll envelope reducer, which only tracks live
  status, not the persisted result id.

## Out of scope

- Auth/access control changes (matches existing no-auth browser surface).
- Editing, deleting, or re-uploading evidence.
- Video/other evidence types beyond the two already supported
  (`SCREENSHOT`, `LOG`).
