# Evidence Viewer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user view captured evidence (screenshots/logs) for a failed test case on the Execute screen, via a new pair of read-only backend endpoints and an in-app modal.

**Architecture:** Two new unauthenticated `GET` endpoints (list evidence metadata for a test result; stream one evidence file's bytes) backed by two new read methods on the existing `EvidenceService`. The frontend adds a small `EvidenceModal` component that fetches the list and renders each item inline, wired to the Execute screen's currently-disabled "View Evidence" button.

**Tech Stack:** Spring Boot / JdbcTemplate (backend), React / TanStack Query / CSS Modules (frontend). No new dependencies.

## Global Constraints

- New endpoints are unauthenticated, matching every other browser-facing endpoint (`operations`, `plans`, `executions`) — only Hub-facing writes require `X-Hub-Token` (per spec `docs/superpowers/specs/2026-07-27-evidence-viewer-design.md`).
- List endpoint returns `[]` for a test result with no evidence yet — never a 404 for that case.
- Content endpoint 404s if the DB row or the underlying file is missing.
- View-only: no delete, re-upload, or pagination in this plan.
- Read any `Instant`/`timestamptz` column via `rs.getTimestamp(...).toInstant()`, never `rs.getObject(col, Instant.class)` — pgjdbc does not support the latter for `timestamptz` (see `backend/src/main/java/com/opshub/execution/application/ExecutionService.java:53`, fixed in commit `0d37f3f`).

---

### Task 1: `EvidenceService` read methods

**Files:**
- Modify: `backend/src/main/java/com/opshub/evidence/application/EvidenceService.java`
- Create: `backend/src/main/java/com/opshub/evidence/application/EvidenceNotFoundException.java`
- Test: `backend/src/test/java/com/opshub/evidence/EvidenceServiceTest.java`

**Interfaces:**
- Produces: `EvidenceService.EvidenceSummary(UUID id, String evidenceType, long sizeBytes, String checksum, java.time.Instant createdAt)` record
- Produces: `EvidenceService.listForTestResult(UUID testResultId): List<EvidenceSummary>`
- Produces: `EvidenceService.EvidenceContent(byte[] bytes, String contentType)` record
- Produces: `EvidenceService.loadContent(UUID evidenceId): EvidenceContent`
- Produces: `EvidenceNotFoundException extends RuntimeException` (thrown by `loadContent` when the row or file is missing)

- [ ] **Step 1: Write the failing tests**

Add to `backend/src/test/java/com/opshub/evidence/EvidenceServiceTest.java`, inside the `EvidenceServiceTest` class (after the existing four `@Test` methods, before the closing brace):

```java
    @Test
    void listForTestResultReturnsEmptyListWhenNoneExist() {
        assertThat(evidenceService.listForTestResult(testResultId)).isEmpty();
    }

    @Test
    void listForTestResultReturnsStoredMetadataOrderedByCreation() throws Exception {
        byte[] first = "first-shot".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second-shot".getBytes(StandardCharsets.UTF_8);
        UUID firstId = evidenceService.store(testResultId, "SCREENSHOT", "a.png",
                first.length, sha256Hex(first), new ByteArrayInputStream(first));
        UUID secondId = evidenceService.store(testResultId, "SCREENSHOT", "b.png",
                second.length, sha256Hex(second), new ByteArrayInputStream(second));

        List<EvidenceService.EvidenceSummary> items = evidenceService.listForTestResult(testResultId);

        assertThat(items).extracting(EvidenceService.EvidenceSummary::id).containsExactly(firstId, secondId);
        assertThat(items).extracting(EvidenceService.EvidenceSummary::evidenceType).containsOnly("SCREENSHOT");
        assertThat(items).extracting(EvidenceService.EvidenceSummary::sizeBytes)
                .containsExactly((long) first.length, (long) second.length);
        assertThat(items).extracting(EvidenceService.EvidenceSummary::createdAt).allSatisfy(
                createdAt -> assertThat(createdAt).isNotNull());
    }

    @Test
    void loadContentReturnsBytesAndImageContentTypeForAScreenshot() throws Exception {
        byte[] content = "screenshot-bytes".getBytes(StandardCharsets.UTF_8);
        UUID evidenceId = evidenceService.store(testResultId, "SCREENSHOT", "device.png",
                content.length, sha256Hex(content), new ByteArrayInputStream(content));

        EvidenceService.EvidenceContent loaded = evidenceService.loadContent(evidenceId);

        assertThat(loaded.bytes()).isEqualTo(content);
        assertThat(loaded.contentType()).isEqualTo("image/png");
    }

    @Test
    void loadContentReturnsTextContentTypeForALog() throws Exception {
        byte[] content = "log line one\nlog line two".getBytes(StandardCharsets.UTF_8);
        UUID evidenceId = evidenceService.store(testResultId, "LOG", "device.log",
                content.length, sha256Hex(content), new ByteArrayInputStream(content));

        EvidenceService.EvidenceContent loaded = evidenceService.loadContent(evidenceId);

        assertThat(loaded.bytes()).isEqualTo(content);
        assertThat(loaded.contentType()).isEqualTo("text/plain");
    }

    @Test
    void loadContentThrowsWhenTheEvidenceRowDoesNotExist() {
        assertThatThrownBy(() -> evidenceService.loadContent(UUID.randomUUID()))
                .isInstanceOf(EvidenceNotFoundException.class);
    }
```

Add this import alongside the existing ones at the top of the file:

```java
import java.util.List;
```

- [ ] **Step 2: Run tests to verify they fail (compile error is expected too)**

Run (from repo root, with `JAVA_HOME` pointed at a JDK 21 and via `sudo` for docker socket access — see the note at the end of this plan on running tests in this sandbox):

```bash
./mvnw -pl backend -am -Dtest=EvidenceServiceTest test
```

Expected: compile failure — `listForTestResult`, `loadContent`, `EvidenceSummary`, `EvidenceContent`, and `EvidenceNotFoundException` do not exist yet.

- [ ] **Step 3: Create `EvidenceNotFoundException`**

Create `backend/src/main/java/com/opshub/evidence/application/EvidenceNotFoundException.java`:

```java
package com.opshub.evidence.application;

import java.util.UUID;

public class EvidenceNotFoundException extends RuntimeException {
    public EvidenceNotFoundException(UUID evidenceId) {
        super("Unknown evidence: " + evidenceId);
    }
}
```

- [ ] **Step 4: Implement the read methods**

In `backend/src/main/java/com/opshub/evidence/application/EvidenceService.java`, add these imports alongside the existing ones:

```java
import java.time.Instant;
import java.util.List;
```

Add these two records and two methods just before the closing `}` of the `EvidenceService` class (after the existing `extension(...)` private method, so after line 123 in the current file):

```java
    public record EvidenceSummary(UUID id, String evidenceType, long sizeBytes, String checksum, Instant createdAt) {
    }

    public record EvidenceContent(byte[] bytes, String contentType) {
    }

    public List<EvidenceSummary> listForTestResult(UUID testResultId) {
        return jdbcTemplate.query("""
                        SELECT id, evidence_type, size_bytes, checksum, created_at
                        FROM evidence WHERE test_result_id = ? ORDER BY created_at
                        """, (rs, rowNum) -> new EvidenceSummary(
                        (UUID) rs.getObject("id"), rs.getString("evidence_type"), rs.getLong("size_bytes"),
                        rs.getString("checksum"), rs.getTimestamp("created_at").toInstant()),
                testResultId);
    }

    public EvidenceContent loadContent(UUID evidenceId) {
        String relativePath = jdbcTemplate.query("SELECT relative_path FROM evidence WHERE id = ?",
                rs -> rs.next() ? rs.getString("relative_path") : null, evidenceId);
        if (relativePath == null) {
            throw new EvidenceNotFoundException(evidenceId);
        }
        Path path = evidenceRoot.resolve(relativePath).normalize();
        if (!path.startsWith(evidenceRoot)) {
            throw new EvidenceNotFoundException(evidenceId);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new EvidenceNotFoundException(evidenceId);
        }
        return new EvidenceContent(bytes, contentTypeFor(path.getFileName().toString()));
    }

    private static String contentTypeFor(String filename) {
        int dot = filename.lastIndexOf('.');
        String extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "log", "txt" -> "text/plain";
            default -> "application/octet-stream";
        };
    }
```

`Path`, `Files`, `IOException`, and `Locale` are already imported in this file (used by `store()`/`extension()`).

- [ ] **Step 5: Run tests to verify they pass**

```bash
./mvnw -pl backend -am -Dtest=EvidenceServiceTest test
```

Expected: `Tests run: 9, Failures: 0, Errors: 0` (the existing 4 tests plus the 5 new ones).

If Testcontainers cannot reach a working Docker environment in your sandbox (a "Could not find a valid Docker environment" / API version error unrelated to this code), skip to Task 5's live-verification step instead of blocking here — but do not skip writing or reading through the tests above; they are the executable spec for this task even if this particular sandbox can't run them.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/opshub/evidence/application/EvidenceService.java \
        backend/src/main/java/com/opshub/evidence/application/EvidenceNotFoundException.java \
        backend/src/test/java/com/opshub/evidence/EvidenceServiceTest.java
git commit -m "feat: add EvidenceService.listForTestResult and loadContent"
```

---

### Task 2: Backend read endpoints

**Files:**
- Modify: `backend/src/main/java/com/opshub/evidence/api/EvidenceController.java`
- Create: `backend/src/main/java/com/opshub/evidence/api/EvidenceContentController.java`

**Interfaces:**
- Consumes: `EvidenceService.listForTestResult(UUID): List<EvidenceService.EvidenceSummary>` (Task 1)
- Consumes: `EvidenceService.loadContent(UUID): EvidenceService.EvidenceContent` (Task 1)
- Consumes: `EvidenceNotFoundException` (Task 1)
- Produces: `GET /api/v1/test-results/{testResultId}/evidence` → `200` with `EvidenceItemResponse[]` body: `{id, evidenceType, sizeBytes, checksum, createdAt}[]`
- Produces: `GET /api/v1/evidence/{evidenceId}/content` → `200` with raw bytes and the correct `Content-Type`, or `404`

No existing test precedent uses MockMvc against `EvidenceController` (`EvidenceServiceTest` tests the service layer directly). Follow that same layer boundary here — routing/status/header correctness for these two endpoints is verified live against the deployed stack in Task 5, the same way the `queued_at` and evidence-upload-permissions fixes earlier in this session were verified (no separate controller test file for this task).

- [ ] **Step 1: Add the list endpoint to the existing controller**

In `backend/src/main/java/com/opshub/evidence/api/EvidenceController.java`, add this import:

```java
import org.springframework.web.bind.annotation.GetMapping;
```

Add this method to the `EvidenceController` class, after the existing `upload(...)` method (after line 56, before the `EvidenceResponse` record):

```java
    @GetMapping
    public List<EvidenceItemResponse> list(@PathVariable UUID testResultId) {
        return evidenceService.listForTestResult(testResultId).stream()
                .map(EvidenceItemResponse::from)
                .toList();
    }
```

Add this import for `List`:

```java
import java.util.List;
```

Add this record next to the existing `EvidenceResponse` record (inside `EvidenceController`, after it):

```java
    public record EvidenceItemResponse(UUID id, String evidenceType, long sizeBytes, String checksum, Instant createdAt) {
        static EvidenceItemResponse from(EvidenceService.EvidenceSummary summary) {
            return new EvidenceItemResponse(summary.id(), summary.evidenceType(), summary.sizeBytes(),
                    summary.checksum(), summary.createdAt());
        }
    }
```

Add this import:

```java
import java.time.Instant;
```

- [ ] **Step 2: Map `EvidenceNotFoundException` to 404**

In the same file, add this handler to the `EvidenceErrorHandler` class (after the existing `invalidToken()` handler, before the `ErrorResponse` record):

```java
    @ExceptionHandler(com.opshub.evidence.application.EvidenceNotFoundException.class)
    ResponseEntity<Void> notFound() {
        return ResponseEntity.notFound().build();
    }
```

- [ ] **Step 3: Create the content-serving controller**

Create `backend/src/main/java/com/opshub/evidence/api/EvidenceContentController.java`:

```java
package com.opshub.evidence.api;

import com.opshub.evidence.application.EvidenceService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Browser-facing evidence content endpoint - unlike EvidenceController's upload endpoint,
 * this is a plain read with no Hub token check, matching every other browser-facing GET
 * (operations, plans, executions).
 */
@RestController
@RequestMapping("/api/v1/evidence/{evidenceId}/content")
public class EvidenceContentController {
    private final EvidenceService evidenceService;

    public EvidenceContentController(EvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    @GetMapping
    public ResponseEntity<byte[]> get(@PathVariable UUID evidenceId) {
        EvidenceService.EvidenceContent content = evidenceService.loadContent(evidenceId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, content.contentType())
                .body(content.bytes());
    }
}
```

`EvidenceNotFoundException` thrown by `loadContent` is mapped to 404 by the `@RestControllerAdvice` `EvidenceErrorHandler` already declared in `EvidenceController.java` — Spring applies `@RestControllerAdvice` classes across all controllers in the application context, not just the one in the same file, so no changes are needed to `EvidenceContentController` itself for the 404 case. `MediaType` is unused in this minimal version; remove that import if your IDE flags it (it isn't referenced above — drop it).

- [ ] **Step 4: Compile**

```bash
./mvnw -pl backend -am -DskipTests compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/opshub/evidence/api/EvidenceController.java \
        backend/src/main/java/com/opshub/evidence/api/EvidenceContentController.java
git commit -m "feat: add evidence list and content-serving endpoints"
```

---

### Task 3: Frontend types, client method, and `EvidenceModal` component

**Files:**
- Modify: `frontend/src/api/generated.ts`
- Modify: `frontend/src/api/client.ts`
- Create: `frontend/src/features/execution/EvidenceModal.tsx`
- Create: `frontend/src/features/execution/EvidenceModal.module.css`

**Interfaces:**
- Produces: `EvidenceItem` type: `{id: Uuid, evidenceType: string, sizeBytes: number, checksum: string, createdAt: string}`
- Produces: `apiClient.listEvidence(testResultId: string): Promise<EvidenceItem[]>`
- Produces: `EvidenceModal` component, props `{testResultId: string, onClose: () => void}`

- [ ] **Step 1: Add the `EvidenceItem` type**

In `frontend/src/api/generated.ts`, add this interface right after the `ExecutionTestResult` interface (after line 194, before the `ExecutionStatus` interface):

```typescript
/** One evidence file's metadata, as returned by GET /test-results/{testResultId}/evidence. */
export interface EvidenceItem {
  id: Uuid;
  evidenceType: string;
  sizeBytes: number;
  checksum: string;
  createdAt: string;
}
```

- [ ] **Step 2: Add the client method**

In `frontend/src/api/client.ts`, add `EvidenceItem` to the type-only import block at the top (alphabetically among the existing names):

```typescript
  EvidenceItem,
```

Add this method to the `apiClient` object, after `getExecution(...)` (the last method before the closing `};`):

```typescript
  listEvidence(testResultId: string): Promise<EvidenceItem[]> {
    return request<EvidenceItem[]>(`/test-results/${testResultId}/evidence`);
  },
```

- [ ] **Step 3: Write the `EvidenceModal` component**

Create `frontend/src/features/execution/EvidenceModal.tsx`:

```tsx
import { useEffect, type ReactElement } from "react";
import { useQuery } from "@tanstack/react-query";
import { apiClient } from "../../api/client";
import styles from "./EvidenceModal.module.css";

export interface EvidenceModalProps {
  testResultId: string;
  onClose: () => void;
}

export function EvidenceModal({ testResultId, onClose }: EvidenceModalProps): ReactElement {
  const query = useQuery({
    queryKey: ["evidence", testResultId] as const,
    queryFn: () => apiClient.listEvidence(testResultId),
  });

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent): void {
      if (event.key === "Escape") {
        onClose();
      }
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  const items = query.data ?? [];

  return (
    <div className={styles.overlay} role="presentation" onClick={onClose}>
      <div
        className={styles.dialog}
        role="dialog"
        aria-modal="true"
        aria-label="Evidence"
        onClick={(event) => event.stopPropagation()}
      >
        <div className={styles.header}>
          <h2 className={styles.title}>Evidence</h2>
          <button type="button" className={styles.closeButton} onClick={onClose} aria-label="Close">
            ×
          </button>
        </div>

        {query.isLoading ? <p className={styles.status}>Loading…</p> : null}
        {query.isError ? <p className={styles.status}>Could not load evidence.</p> : null}
        {query.isSuccess && items.length === 0 ? (
          <p className={styles.status}>No evidence available.</p>
        ) : null}

        <div className={styles.items}>
          {items.map((item) => (
            <EvidenceItemView key={item.id} item={item} />
          ))}
        </div>
      </div>
    </div>
  );
}

function EvidenceItemView({ item }: { item: { id: string; evidenceType: string; createdAt: string } }): ReactElement {
  const contentUrl = `/api/v1/evidence/${item.id}/content`;
  return (
    <div className={styles.item}>
      <p className={styles.itemMeta}>
        {item.evidenceType} — {new Date(item.createdAt).toLocaleString()}
      </p>
      {item.evidenceType === "LOG" ? (
        <LogContent url={contentUrl} />
      ) : (
        <img className={styles.screenshot} src={contentUrl} alt={`${item.evidenceType} evidence`} />
      )}
    </div>
  );
}

function LogContent({ url }: { url: string }): ReactElement {
  const query = useQuery({
    queryKey: ["evidence-log", url] as const,
    queryFn: async () => {
      const response = await fetch(url);
      if (!response.ok) {
        throw new Error(`Failed to load log: ${response.status}`);
      }
      return response.text();
    },
  });
  if (query.isLoading) {
    return <p className={styles.status}>Loading log…</p>;
  }
  if (query.isError) {
    return <p className={styles.status}>Could not load log.</p>;
  }
  return <pre className={styles.log}>{query.data}</pre>;
}
```

- [ ] **Step 4: Write the modal's stylesheet**

Create `frontend/src/features/execution/EvidenceModal.module.css`:

```css
.overlay {
  position: fixed;
  inset: 0;
  background: rgb(0 0 0 / 50%);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: var(--ops-z-modal, 1000);
  padding: var(--ops-space-4);
}

.dialog {
  background: var(--ops-color-white);
  border-radius: var(--ops-radius-md);
  max-width: 720px;
  width: 100%;
  max-height: 85vh;
  overflow-y: auto;
  padding: var(--ops-space-5);
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--ops-space-4);
}

.title {
  font-size: var(--ops-font-size-lg);
  font-weight: var(--ops-font-weight-semibold);
  margin: 0;
}

.closeButton {
  background: none;
  border: none;
  font-size: var(--ops-font-size-lg);
  line-height: 1;
  cursor: pointer;
  padding: var(--ops-space-1) var(--ops-space-2);
}

.status {
  color: var(--ops-color-gray-600);
}

.items {
  display: flex;
  flex-direction: column;
  gap: var(--ops-space-4);
}

.item {
  border: 1px solid var(--ops-color-gray-200);
  border-radius: var(--ops-radius-md);
  padding: var(--ops-space-3);
}

.itemMeta {
  font-size: var(--ops-font-size-sm);
  color: var(--ops-color-gray-600);
  margin: 0 0 var(--ops-space-2);
}

.screenshot {
  max-width: 100%;
  border-radius: var(--ops-radius-sm);
  display: block;
}

.log {
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--ops-color-gray-50, #f8f8f8);
  padding: var(--ops-space-3);
  border-radius: var(--ops-radius-sm);
  font-size: var(--ops-font-size-sm);
  margin: 0;
}
```

If `--ops-z-modal` or `--ops-color-gray-50` are not defined in the project's design tokens, the fallback values given (`1000`, `#f8f8f8`) apply automatically via CSS `var(--name, fallback)` — no need to touch the tokens file.

- [ ] **Step 5: Build the frontend to catch type errors**

```bash
cd frontend && npm run build
```

Expected: build succeeds (this component isn't wired into any screen yet, but TypeScript still type-checks it since it's part of the project).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/generated.ts frontend/src/api/client.ts \
        frontend/src/features/execution/EvidenceModal.tsx \
        frontend/src/features/execution/EvidenceModal.module.css
git commit -m "feat: add EvidenceModal component and listEvidence client method"
```

---

### Task 4: Wire `ExecuteScreen`'s "View Evidence" button

**Files:**
- Modify: `frontend/src/features/execution/ExecuteScreen.tsx`
- Test: `frontend/src/features/execution/ExecuteScreen.test.tsx`

**Interfaces:**
- Consumes: `EvidenceModal` (Task 3), props `{testResultId: string, onClose: () => void}`
- Consumes: `execution.results: ExecutionTestResult[]` (already fetched by `useExecutionQuery`, already in scope in this component)

- [ ] **Step 1: Replace the now-obsolete "always disabled" test and add two new ones**

The existing test at the bottom of the `describe("ExecuteScreen", ...)` block (currently named `"shows a disabled evidence control with an explanatory tooltip for a failed test case (no browser-facing evidence viewer exists yet)"`) asserts the button is *always* `disabled`. That premise becomes false once this task wires the button, so replace that whole test (not just add a new one alongside it) with the three tests below, inserted at the same location in `frontend/src/features/execution/ExecuteScreen.test.tsx`:

```typescript
  it("shows no evidence control for a failed test case until its result has been fetched", async () => {
    renderScreen({ seedPlan: plan({ testCases: fiveCasesFor(1) }), seedExecution: execution() });
    const dispatchEnvelope = channelSpy.mock.calls[0][0].onEnvelope as (envelope: unknown) => void;

    dispatchEnvelope({
      messageId: "m1",
      version: 1,
      type: "TEST_RESULT",
      timestamp: "2026-07-27T00:00:01Z",
      payload: { executionId: "exec-1", testCaseId: "tc-1-1", attempt: 1, status: "FAILED", durationMs: 500, errorCategory: "ASSERTION_FAILURE" },
    });

    await waitFor(() => expect(screen.getByText("Failed")).toBeInTheDocument());
    expect(screen.queryByRole("button", { name: "View Evidence" })).not.toBeInTheDocument();
  });

  it("enables View Evidence once the failed test case's result is fetched, and opens the evidence modal", async () => {
    const { queryClient } = renderScreen({ seedPlan: plan({ testCases: fiveCasesFor(1) }), seedExecution: execution() });
    const dispatchEnvelope = channelSpy.mock.calls[0][0].onEnvelope as (envelope: unknown) => void;
    await waitFor(() => expect(mocks.getExecution).toHaveBeenCalled());

    dispatchEnvelope({
      messageId: "m1",
      version: 1,
      type: "TEST_RESULT",
      timestamp: "2026-07-27T00:00:01Z",
      payload: { executionId: "exec-1", testCaseId: "tc-1-1", attempt: 1, status: "FAILED", durationMs: 500, errorCategory: "ASSERTION_FAILURE" },
    });
    mocks.getExecution.mockResolvedValue({
      ...execution(),
      results: [
        { id: "result-1", testCaseId: "tc-1-1", attempt: 1, status: "FAILED", durationMs: 500, errorCategory: "ASSERTION_FAILURE" },
      ],
    });
    await queryClient.refetchQueries({ queryKey: executionQueryKey("op-1") });
    mocks.listEvidence.mockResolvedValue([]);

    const evidenceButton = await screen.findByRole("button", { name: "View Evidence" });
    expect(evidenceButton).toBeEnabled();
    await userEvent.click(evidenceButton);

    expect(await screen.findByRole("dialog", { name: "Evidence" })).toBeInTheDocument();
    expect(mocks.listEvidence).toHaveBeenCalledWith("result-1");
  });

  it("closes the evidence modal on Escape", async () => {
    const { queryClient } = renderScreen({ seedPlan: plan({ testCases: fiveCasesFor(1) }), seedExecution: execution() });
    const dispatchEnvelope = channelSpy.mock.calls[0][0].onEnvelope as (envelope: unknown) => void;
    await waitFor(() => expect(mocks.getExecution).toHaveBeenCalled());
    dispatchEnvelope({
      messageId: "m1",
      version: 1,
      type: "TEST_RESULT",
      timestamp: "2026-07-27T00:00:01Z",
      payload: { executionId: "exec-1", testCaseId: "tc-1-1", attempt: 1, status: "FAILED", durationMs: 500, errorCategory: "ASSERTION_FAILURE" },
    });
    mocks.getExecution.mockResolvedValue({
      ...execution(),
      results: [
        { id: "result-1", testCaseId: "tc-1-1", attempt: 1, status: "FAILED", durationMs: 500, errorCategory: "ASSERTION_FAILURE" },
      ],
    });
    await queryClient.refetchQueries({ queryKey: executionQueryKey("op-1") });
    mocks.listEvidence.mockResolvedValue([]);
    await userEvent.click(await screen.findByRole("button", { name: "View Evidence" }));
    await screen.findByRole("dialog", { name: "Evidence" });

    await userEvent.keyboard("{Escape}");

    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Evidence" })).not.toBeInTheDocument());
  });
```

This file's `mocks` object (defined near the top via `vi.hoisted`) needs a `listEvidence` entry alongside the existing `getOperation`/`startExecution`/`getPlan`/`getExecution` ones — add it now:

```typescript
const mocks = vi.hoisted(() => ({
  getOperation: vi.fn(),
  startExecution: vi.fn(),
  getPlan: vi.fn(),
  getExecution: vi.fn(),
  listEvidence: vi.fn(),
}));
```

(This replaces the existing 5-line `mocks` declaration near the top of the file — same `vi.mock("../../api/client", ...)` block below it needs no other changes, since it already spreads `mocks` as the whole `apiClient`.)

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd frontend && npm test -- ExecuteScreen
```

Expected: FAIL — no `View Evidence` button exists at all yet for any state (the current code always renders it, just `disabled`), and `EvidenceModal`/`mocks.listEvidence` aren't wired to anything.

- [ ] **Step 3: Wire the button**

In `frontend/src/features/execution/ExecuteScreen.tsx`:

Add this import:

```typescript
import { EvidenceModal } from "./EvidenceModal";
```

Add this state near the other `useState` calls (with `tab` and `startError`, around line 77-78):

```typescript
  const [evidenceTestResultId, setEvidenceTestResultId] = useState<string | null>(null);
```

Replace the evidence cell's `<td>` (currently lines 289-301, the block starting `{isFailure ? (` and using a `disabled` button) with:

```tsx
                          <td>
                            {isFailure ? (
                              (() => {
                                const result = (execution?.results ?? [])
                                  .filter((candidate) => candidate.testCaseId === testCase.testCaseId)
                                  .sort((a, b) => b.attempt - a.attempt)[0];
                                return result ? (
                                  <button
                                    type="button"
                                    className={styles.evidenceButton}
                                    onClick={() => setEvidenceTestResultId(result.id)}
                                  >
                                    View Evidence
                                  </button>
                                ) : (
                                  "—"
                                );
                              })()
                            ) : (
                              "—"
                            )}
                          </td>
```

Add the modal render, right before the closing `</div>` of the component's top-level return (after the `</div>` that closes `styles.layout`, i.e. right before the final `</div>` that currently ends the component around line 338-340):

```tsx
      {evidenceTestResultId ? (
        <EvidenceModal testResultId={evidenceTestResultId} onClose={() => setEvidenceTestResultId(null)} />
      ) : null}
```

Add this rule to `frontend/src/features/execution/ExecuteScreen.module.css`, matching the existing secondary-button convention used by `.recheckButton` in `frontend/src/features/validation/VerifyScreen.module.css:27-35`:

```css
.evidenceButton {
  border: 1px solid var(--ops-color-blue-500);
  color: var(--ops-color-blue-600);
  background: var(--ops-color-white);
  border-radius: var(--ops-radius-md);
  padding: var(--ops-space-2) var(--ops-space-4);
  cursor: pointer;
  min-height: var(--ops-min-target-size);
}
```

Remove the now-dead `.evidenceUnavailable` rule from the same file (`frontend/src/features/execution/ExecuteScreen.module.css:194-201`) — it was the disabled placeholder button's styling and nothing references it after Step 3's JSX replacement:

```css
.evidenceUnavailable {
  background: none;
  border: none;
  padding: 0;
  color: var(--ops-color-gray-500);
  cursor: not-allowed;
  text-decoration: underline dotted;
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
cd frontend && npm test -- ExecuteScreen
```

Expected: PASS.

- [ ] **Step 5: Run the full frontend test suite to check for regressions**

```bash
cd frontend && npm test
```

Expected: all tests pass, including the pre-existing `ExecuteScreen.test.tsx` tests (the evidence cell's `"—"` fallback for non-failed rows and for failed rows with no matching result yet must still render — re-read those existing assertions before changing the cell's JSX in Step 4 so this doesn't regress them).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/execution/ExecuteScreen.tsx \
        frontend/src/features/execution/ExecuteScreen.module.css \
        frontend/src/features/execution/ExecuteScreen.test.tsx
git commit -m "feat: wire View Evidence button to EvidenceModal"
```

---

### Task 5: Deploy and verify live

**Files:** none (build/deploy/verification only)

- [ ] **Step 1: Rebuild both images**

```bash
cd deploy && sudo docker compose -f compose.yaml build backend frontend
```

Expected: both builds succeed.

- [ ] **Step 2: Redeploy**

```bash
sudo docker compose -f compose.yaml up -d backend frontend
sleep 10
sudo docker compose -f compose.yaml exec -T backend curl -fsS http://127.0.0.1:8080/actuator/health/readiness
```

Expected: `{"status":"UP"}`.

- [ ] **Step 3: Verify the list endpoint against a real evidence row**

Find a `test_results.id` that already has evidence (e.g. via `sudo docker exec deploy-postgres-1 psql -U opshub -d opshub -tAc "SELECT DISTINCT test_result_id FROM evidence LIMIT 1;"`), then:

```bash
curl -s "http://localhost/api/v1/test-results/<that-id>/evidence" | python3 -m json.tool
```

Expected: a JSON array with at least one item, each having `id`, `evidenceType`, `sizeBytes`, `checksum`, `createdAt`.

- [ ] **Step 4: Verify the content endpoint**

```bash
curl -s -o /tmp/evidence-check.png -D - "http://localhost/api/v1/evidence/<an-id-from-step-3>/content" | head -20
file /tmp/evidence-check.png
```

Expected: `Content-Type: image/png` header, and `file` reports it as a valid PNG (or matches whatever `evidenceType` was returned for that item).

- [ ] **Step 5: Verify the 404 case**

```bash
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost/api/v1/evidence/00000000-0000-0000-0000-000000000000/content"
```

Expected: `404`.

- [ ] **Step 6: Clean up the verification artifact**

```bash
rm -f /tmp/evidence-check.png
```

- [ ] **Step 7: Commit anything still pending**

```bash
git status
```

If Task 1-4's commits were all made along the way, there should be nothing left to commit here — this step is a final check, not expected to produce a new commit.
