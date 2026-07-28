# Unified Multi-Platform Hub Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let one Local Hub process, under one `hub_id`, run Android and Web execution sessions concurrently and independently — no restart needed to add/switch platforms — replacing today's one-platform-per-Hub-identity model.

**Architecture:** Move Hub connection/readiness state from a single `hubs` row to a new `hub_platforms` table keyed by `(hub_id, platform)`; widen `job_leases`' uniqueness from `(hub_id)` to `(hub_id, platform)`; thread an explicit `platform` parameter through every backend call that used to read a single `hubs.platform` column. On the Local Hub side, replace the single frozen `platform` config value with a `platforms` list and run one independent thread per configured platform, each reusing today's per-platform pipeline (preflight, Runner, transports) unchanged. Retire `WebWorkerLauncher` — Web becomes one of the Hub's own always-on sessions instead of a backend-spawned subprocess.

**Tech Stack:** Java 21 / Spring Boot / JdbcTemplate / Flyway / Postgres (backend); Python 3.11 / pydantic / httpx / websockets (Local Hub); React / TypeScript / Vitest (frontend).

## Global Constraints

- One session per platform per Hub (not per-Hub-total) — matches "one Android device + one Chrome profile" as the natural resource limit. No support for two concurrent sessions of the *same* platform.
- This is a clean, non-backward-compatible migration: old `hubs` columns are dropped after backfilling `hub_platforms`, no dual-write period.
- iOS and PC stay out of scope; only `ANDROID` and `WEB` are valid platform values in this plan.
- Every existing test that references a changed signature must be updated in the same task as the signature change — never leave a task with the build red.

---

### Task 1: `hub_platforms` table + `HubConnectionService` rewrite

**Files:**
- Create: `backend/src/main/resources/db/migration/V8__hub_platforms.sql`
- Modify: `backend/src/main/java/com/opshub/hub/application/HubConnectionService.java`
- Modify: `backend/src/test/java/com/opshub/hub/HubConnectionServiceTest.java`

**Interfaces:**
- Produces: `HubConnectionService.markOnline(UUID hubId, String transport, String platform)` (signature unchanged), `heartbeat(UUID hubId, String transport, boolean deviceReady, boolean runnerReady, String platform)` (signature unchanged), `markOffline(UUID hubId, String platform)` (**new parameter** — every existing caller must be updated).

- [ ] **Step 1: Write the migration**

```sql
-- backend/src/main/resources/db/migration/V8__hub_platforms.sql

-- A Hub's connection/readiness state used to be one column per hub row, assuming exactly
-- one platform per Hub. It now lives here, one row per (hub, platform), so two platforms
-- under the same hub_id can independently connect/disconnect/report readiness without
-- clobbering each other (see docs/superpowers/specs/2026-07-28-unified-multiplatform-hub-design.md).
CREATE TABLE hub_platforms (
    hub_id UUID NOT NULL REFERENCES hubs(id),
    platform VARCHAR(16) NOT NULL CHECK (platform IN ('ANDROID', 'WEB')),
    connection_status VARCHAR(64) NOT NULL,
    transport VARCHAR(64) NOT NULL,
    last_heartbeat_at TIMESTAMP WITH TIME ZONE,
    device_ready BOOLEAN NOT NULL DEFAULT FALSE,
    runner_ready BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (hub_id, platform)
);

INSERT INTO hub_platforms (hub_id, platform, connection_status, transport, last_heartbeat_at, device_ready, runner_ready)
SELECT id, platform, connection_status, transport, last_heartbeat_at, device_ready, runner_ready
FROM hubs;

ALTER TABLE hubs
    DROP COLUMN connection_status,
    DROP COLUMN transport,
    DROP COLUMN last_heartbeat_at,
    DROP COLUMN device_ready,
    DROP COLUMN runner_ready,
    DROP COLUMN platform;

-- job_leases: one active lease per (hub, platform) instead of per hub - the actual
-- concurrency unlock, letting a Hub hold an ANDROID lease and a WEB lease at once.
ALTER TABLE job_leases
    ADD COLUMN platform VARCHAR(16) NOT NULL DEFAULT 'ANDROID' CHECK (platform IN ('ANDROID', 'WEB'));

ALTER TABLE job_leases DROP CONSTRAINT job_leases_hub_id_unique;
ALTER TABLE job_leases ADD CONSTRAINT job_leases_hub_id_platform_unique UNIQUE (hub_id, platform);
```

- [ ] **Step 2: Write the failing test proving two platforms coexist under one hub_id**

Add to `HubConnectionServiceTest.java` (this test needs a real Postgres, so convert the class to a Testcontainers test — replace the whole file):

```java
package com.opshub.hub;

import com.opshub.hub.application.HubConnectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class HubConnectionServiceTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private HubConnectionService hubConnectionService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void marksOnlineIndependentlyPerPlatformUnderTheSameHubId() {
        UUID hubId = UUID.randomUUID();

        hubConnectionService.markOnline(hubId, "WEBSOCKET", "ANDROID");
        hubConnectionService.markOnline(hubId, "WEBSOCKET", "WEB");

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM hub_platforms WHERE hub_id = ?", Integer.class, hubId);
        assertThat(rowCount).isEqualTo(2);

        String androidStatus = jdbcTemplate.queryForObject(
                "SELECT connection_status FROM hub_platforms WHERE hub_id = ? AND platform = 'ANDROID'",
                String.class, hubId);
        assertThat(androidStatus).isEqualTo("ONLINE");
    }

    @Test
    void heartbeatUpdatesOnlyItsOwnPlatformsReadiness() {
        UUID hubId = UUID.randomUUID();
        hubConnectionService.markOnline(hubId, "WEBSOCKET", "ANDROID");
        hubConnectionService.markOnline(hubId, "WEBSOCKET", "WEB");

        hubConnectionService.heartbeat(hubId, "WEBSOCKET", true, true, "ANDROID");
        hubConnectionService.heartbeat(hubId, "WEBSOCKET", false, false, "WEB");

        Boolean androidDeviceReady = jdbcTemplate.queryForObject(
                "SELECT device_ready FROM hub_platforms WHERE hub_id = ? AND platform = 'ANDROID'",
                Boolean.class, hubId);
        Boolean webDeviceReady = jdbcTemplate.queryForObject(
                "SELECT device_ready FROM hub_platforms WHERE hub_id = ? AND platform = 'WEB'",
                Boolean.class, hubId);
        assertThat(androidDeviceReady).isTrue();
        assertThat(webDeviceReady).isFalse();
    }

    @Test
    void markOfflineAffectsOnlyTheGivenPlatform() {
        UUID hubId = UUID.randomUUID();
        hubConnectionService.markOnline(hubId, "WEBSOCKET", "ANDROID");
        hubConnectionService.markOnline(hubId, "WEBSOCKET", "WEB");

        hubConnectionService.markOffline(hubId, "ANDROID");

        String androidStatus = jdbcTemplate.queryForObject(
                "SELECT connection_status FROM hub_platforms WHERE hub_id = ? AND platform = 'ANDROID'",
                String.class, hubId);
        String webStatus = jdbcTemplate.queryForObject(
                "SELECT connection_status FROM hub_platforms WHERE hub_id = ? AND platform = 'WEB'",
                String.class, hubId);
        assertThat(androidStatus).isEqualTo("OFFLINE");
        assertThat(webStatus).isEqualTo("ONLINE");
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=HubConnectionServiceTest`
Expected: FAIL — `markOffline(UUID, String)` doesn't exist yet, and `hub_platforms` isn't written to yet.

- [ ] **Step 4: Rewrite `HubConnectionService`**

```java
package com.opshub.hub.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Tracks Hub connectivity and heartbeat state, one row per (hub, platform) in
 * hub_platforms - a Hub is created on first contact (WebSocket connect or first poll) so the
 * Python Local Hub does not need a separate registration step.
 */
@Service
public class HubConnectionService {
    private final JdbcTemplate jdbcTemplate;

    public HubConnectionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String DEFAULT_PLATFORM = "ANDROID";

    public void markOnline(UUID hubId, String transport, String platform) {
        upsert(hubId, transport, "ONLINE", null, null, platform);
    }

    public void markOffline(UUID hubId, String platform) {
        jdbcTemplate.update(
                "UPDATE hub_platforms SET connection_status = 'OFFLINE' WHERE hub_id = ? AND platform = ?",
                hubId, normalize(platform));
    }

    public void heartbeat(UUID hubId, String transport, boolean deviceReady, boolean runnerReady, String platform) {
        upsert(hubId, transport, "ONLINE", deviceReady, runnerReady, platform);
    }

    private void upsert(UUID hubId, String transport, String status, Boolean deviceReady, Boolean runnerReady, String platform) {
        String normalizedPlatform = normalize(platform);
        Timestamp heartbeatAt = Timestamp.from(Instant.now());

        jdbcTemplate.update("""
                        INSERT INTO hubs (id, name, created_at)
                        VALUES (?, ?, ?)
                        ON CONFLICT (id) DO NOTHING
                        """, hubId, hubId.toString(), heartbeatAt);

        int updated = jdbcTemplate.update("""
                        UPDATE hub_platforms
                        SET connection_status = ?, transport = ?, last_heartbeat_at = ?,
                            device_ready = COALESCE(?, device_ready), runner_ready = COALESCE(?, runner_ready)
                        WHERE hub_id = ? AND platform = ?
                        """, status, transport, heartbeatAt, deviceReady, runnerReady, hubId, normalizedPlatform);
        if (updated == 0) {
            jdbcTemplate.update("""
                            INSERT INTO hub_platforms
                                (hub_id, platform, connection_status, transport, last_heartbeat_at, device_ready, runner_ready)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """, hubId, normalizedPlatform, status, transport, heartbeatAt,
                    deviceReady != null && deviceReady, runnerReady != null && runnerReady);
        }
    }

    // The X-Hub-Platform header (or WS handshake attribute) is caller-supplied and not
    // otherwise validated before reaching here - hub_platforms.platform has a CHECK
    // constraint (ANDROID/WEB only), so anything else would fail this call on every
    // poll/heartbeat. Fall back to the documented default rather than let a stray/malformed
    // header value take a platform permanently un-upsertable.
    private static String normalize(String platform) {
        return "WEB".equals(platform) ? "WEB" : DEFAULT_PLATFORM;
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=HubConnectionServiceTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V8__hub_platforms.sql \
        backend/src/main/java/com/opshub/hub/application/HubConnectionService.java \
        backend/src/test/java/com/opshub/hub/HubConnectionServiceTest.java
git commit -m "feat: track Hub connection state per (hub, platform) in hub_platforms"
```

---

### Task 2: `LeaseService` platform-scoping

**Files:**
- Modify: `backend/src/main/java/com/opshub/execution/application/LeaseService.java`
- Modify: `backend/src/test/java/com/opshub/execution/LeaseServiceTest.java`

**Interfaces:**
- Consumes: nothing new from Task 1.
- Produces: `hasActiveLease(UUID hubId, String platform)`, `acquire(UUID hubId, String platform, UUID executionId)`, `renewActiveLease(UUID hubId, String platform)` — all gain a `platform` parameter. `renew(UUID hubId, UUID leaseToken)`, `release(UUID executionId)`, `nextOfferableExecution(String platform)` are unchanged (already unambiguous without it).

- [ ] **Step 1: Update the failing tests**

Replace `LeaseServiceTest.java`:

```java
package com.opshub.execution;

import com.opshub.execution.application.LeaseService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeaseServiceTest {

    @Test
    void bindsActiveLeaseCutoffAsJdbcTimestamp() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);

        new LeaseService(jdbcTemplate).hasActiveLease(UUID.randomUUID(), "ANDROID");

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(anyString(), eq(Integer.class), arguments.capture());
        assertThat(arguments.getValue()[1]).isEqualTo("ANDROID");
        assertThat(arguments.getValue()[2]).isInstanceOf(Timestamp.class);
    }

    @Test
    void bindsLeaseRenewalTimesAsJdbcTimestamps() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        new LeaseService(jdbcTemplate).renewActiveLease(UUID.randomUUID(), "WEB");

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), arguments.capture());
        assertThat(arguments.getValue()[0]).isInstanceOf(Timestamp.class);
        assertThat(arguments.getValue()[2]).isEqualTo("WEB");
        assertThat(arguments.getValue()[3]).isInstanceOf(Timestamp.class);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=LeaseServiceTest`
Expected: FAIL — `hasActiveLease(UUID, String)`/`renewActiveLease(UUID, String)` don't exist yet.

- [ ] **Step 3: Rewrite `LeaseService`**

```java
package com.opshub.execution.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Database-backed leases granting a single Hub exclusive ownership of an execution, scoped per
 * (hub, platform) - a Hub can hold one active ANDROID lease and one active WEB lease at once,
 * but never two leases for the same platform.
 */
@Service
public class LeaseService {
    static final Duration LEASE_DURATION = Duration.ofSeconds(60);

    private final JdbcTemplate jdbcTemplate;

    public LeaseService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean hasActiveLease(UUID hubId, String platform) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM job_leases WHERE hub_id = ? AND platform = ? AND expires_at > ?
                        """, Integer.class, hubId, platform, now());
        return count != null && count > 0;
    }

    /**
     * Grants a new lease for the (hub, platform) pair. Any previously expired lease for that
     * same pair is deleted first so at most one row per (hub_id, platform) ever exists, letting
     * the database-level unique constraint (job_leases_hub_id_platform_unique) enforce "one
     * active lease per platform per Hub" even across multiple backend instances.
     */
    public UUID acquire(UUID hubId, String platform, UUID executionId) {
        jdbcTemplate.update("DELETE FROM job_leases WHERE hub_id = ? AND platform = ? AND expires_at <= ?",
                hubId, platform, now());
        UUID leaseToken = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO job_leases (id, hub_id, platform, execution_id, lease_token, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), hubId, platform, executionId, leaseToken, expiresAt());
        return leaseToken;
    }

    public boolean renew(UUID hubId, UUID leaseToken) {
        int updated = jdbcTemplate.update("""
                        UPDATE job_leases
                        SET expires_at = ?
                        WHERE lease_token = ? AND hub_id = ? AND expires_at > ?
                        """, expiresAt(), leaseToken, hubId, now());
        return updated == 1;
    }

    public boolean renewActiveLease(UUID hubId, String platform) {
        int updated = jdbcTemplate.update("""
                        UPDATE job_leases
                        SET expires_at = ?
                        WHERE hub_id = ? AND platform = ? AND expires_at > ?
                        """, expiresAt(), hubId, platform, now());
        return updated == 1;
    }

    public void release(UUID executionId) {
        jdbcTemplate.update("DELETE FROM job_leases WHERE execution_id = ?", executionId);
    }

    public Optional<UUID> nextOfferableExecution(String platform) {
        return jdbcTemplate.queryForList("""
                        SELECT execution.id
                        FROM executions execution
                        WHERE execution.status IN ('QUEUED', 'RUNNING')
                          AND execution.finished_at IS NULL
                          AND EXISTS (
                              SELECT 1 FROM official_accounts oa
                              WHERE oa.operation_id = execution.operation_id AND oa.platform = ?
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM job_leases lease
                              WHERE lease.execution_id = execution.id AND lease.expires_at > ?
                          )
                        ORDER BY execution.queued_at ASC
                        LIMIT 1
                        """, UUID.class, platform, now())
                .stream().findFirst();
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    private static Timestamp expiresAt() {
        return Timestamp.from(Instant.now().plus(LEASE_DURATION));
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=LeaseServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/opshub/execution/application/LeaseService.java \
        backend/src/test/java/com/opshub/execution/LeaseServiceTest.java
git commit -m "feat: scope job leases per (hub, platform) instead of per hub"
```

---

### Task 3: `ExecutionService` platform-scoping + drop the Web-worker spawn

**Files:**
- Modify: `backend/src/main/java/com/opshub/execution/application/ExecutionService.java`
- Modify: `backend/src/test/java/com/opshub/execution/ExecutionServiceTest.java`

**Interfaces:**
- Consumes: `LeaseService.hasActiveLease(hubId, platform)`, `acquire(hubId, platform, executionId)`, `renewActiveLease(hubId, platform)` from Task 2.
- Produces: `ExecutionService.offerNextJob(UUID hubId, String platform)` (**new parameter**), `renewActiveLease(UUID hubId, String platform)` (**new parameter**). `ExecutionService`'s constructor drops its `WebWorkerLauncher` parameter entirely.

- [ ] **Step 1: Update `ExecutionServiceTest` for the new signatures**

Every existing call to `executionService.offerNextJob(hubId)` becomes `executionService.offerNextJob(hubId, "ANDROID")` (or `"WEB"` where the test already uses a WEB operation), and every `executionService.renewActiveLease(hubId)` becomes `executionService.renewActiveLease(hubId, "ANDROID")`. Apply this mechanical replacement across the whole file (17 call sites), for example:

```java
    @Test
    void requiresAnOnlineHubBeforeOfferingAJob() {
        UUID operationId = createDraftOperation("MOB-603");
        approvePlan(operationId, 1);
        executionService.start(operationId, 1, "key-offline");
        UUID hubId = UUID.randomUUID();

        assertThatThrownBy(() -> executionService.offerNextJob(hubId, "ANDROID"))
                .isInstanceOf(HubNotOnlineException.class);
    }
```

```java
    @Test
    void reportsTheOperationsActualPlatformInTheJobOfferedPayload() {
        UUID operationId = createDraftOperation("MOB-607");
        approvePlan(operationId, 1, "WEB");
        executionService.start(operationId, 1, "key-web-platform");
        UUID hubId = UUID.randomUUID();
        hubConnectionService.markOnline(hubId, "WEBSOCKET", "WEB");

        Optional<HubEnvelopeV1> offer = executionService.offerNextJob(hubId, "WEB");

        assertThat(offer).isPresent();
        HubPayloads.JobOfferedPayload payload = (HubPayloads.JobOfferedPayload) offer.get().payload();
        assertThat(payload.platform()).isEqualTo("WEB");
    }
```

Also add a new test proving the actual feature — a Hub holding two concurrent leases at once:

```java
    @Test
    void aHubCanHoldOneActiveAndroidLeaseAndOneActiveWebLeaseAtTheSameTime() {
        UUID androidOperationId = createDraftOperation("MOB-620");
        approvePlan(androidOperationId, 1, "ANDROID");
        executionService.start(androidOperationId, 1, "key-concurrent-android");

        UUID webOperationId = createDraftOperation("MOB-621");
        approvePlan(webOperationId, 1, "WEB");
        executionService.start(webOperationId, 1, "key-concurrent-web");

        UUID hubId = UUID.randomUUID();
        hubConnectionService.markOnline(hubId, "WEBSOCKET", "ANDROID");
        hubConnectionService.markOnline(hubId, "WEBSOCKET", "WEB");

        Optional<HubEnvelopeV1> androidOffer = executionService.offerNextJob(hubId, "ANDROID");
        Optional<HubEnvelopeV1> webOffer = executionService.offerNextJob(hubId, "WEB");

        assertThat(androidOffer).isPresent();
        assertThat(webOffer).isPresent();
        // A second ANDROID offer must still be refused - the WEB lease does not block it, but
        // ANDROID's own active lease does.
        assertThat(executionService.offerNextJob(hubId, "ANDROID")).isEmpty();
    }
```

Update the `ExecutionService` constructor call in the test class's `@Autowired` field wiring: none needed, since Spring wires it — but the production constructor change (Step 3 below) removes `WebWorkerLauncher` as a Spring bean dependency, so no test wiring change is required here as long as `WebWorkerLauncher` still exists as a bean until Task 6 deletes it. This task only changes `ExecutionService`'s own constructor signature; leave `WebWorkerLauncher` in place until Task 6.

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ExecutionServiceTest`
Expected: FAIL — `offerNextJob(UUID, String)` doesn't exist yet.

- [ ] **Step 3: Rewrite `ExecutionService`**

Apply these changes to `backend/src/main/java/com/opshub/execution/application/ExecutionService.java`:

1. Constructor: drop the `webWorkerLauncher` field and both constructor parameters/overloads referencing it.
2. `start()`: delete the `String platform = jdbcTemplate.queryForObject(...)` block and the `if ("WEB".equals(platform)) { webWorkerLauncher.launchIfNeeded(); }` call entirely — Web no longer needs an on-demand spawn.
3. `offerNextJob`: takes `platform` explicitly, keys the lock map by `(hubId, platform)`, validates via `hub_platforms` instead of `hubs.platform`:

```java
    private final ConcurrentHashMap<HubPlatformKey, ReentrantLock> hubLocks = new ConcurrentHashMap<>();

    private record HubPlatformKey(UUID hubId, String platform) {
    }

    @Transactional
    public Optional<HubEnvelopeV1> offerNextJob(UUID hubId, String platform) {
        requireHubPlatformOnline(hubId, platform);
        HubPlatformKey key = new HubPlatformKey(hubId, platform);
        ReentrantLock lock = hubLocks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            if (leaseService.hasActiveLease(hubId, platform)) {
                return Optional.empty();
            }
            Optional<UUID> candidate = leaseService.nextOfferableExecution(platform);
            if (candidate.isEmpty()) {
                return Optional.empty();
            }
            UUID executionId = candidate.get();
            UUID leaseToken;
            try {
                leaseToken = leaseService.acquire(hubId, platform, executionId);
            } catch (org.springframework.dao.DataIntegrityViolationException lostRace) {
                return Optional.empty();
            }
            jdbcTemplate.update("""
                            UPDATE executions
                            SET status = 'RUNNING', hub_id = ?, started_at = COALESCE(started_at, ?)
                            WHERE id = ?
                            """, hubId, Timestamp.from(Instant.now()), executionId);
            return Optional.of(buildJobOfferedEnvelope(executionId, leaseToken));
        } finally {
            lock.unlock();
        }
    }

    public boolean renewLease(UUID hubId, UUID leaseToken) {
        requireHubOnline(hubId);
        return leaseService.renew(hubId, leaseToken);
    }

    public boolean renewActiveLease(UUID hubId, String platform) {
        requireHubOnline(hubId);
        return leaseService.renewActiveLease(hubId, platform);
    }
```

4. Replace `requireHubOnlineAndGetPlatform`/`requireHubOnline` with two helpers, one checking the Hub identity exists (still needed by `renewLease`/`renewActiveLease`, which don't need a specific platform to know the Hub itself is connected) and one checking a specific platform is online:

```java
    private void requireHubOnline(UUID hubId) {
        Integer onlinePlatformCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM hub_platforms WHERE hub_id = ? AND connection_status = 'ONLINE'",
                Integer.class, hubId);
        if (onlinePlatformCount == null || onlinePlatformCount == 0) {
            throw new HubNotOnlineException(hubId);
        }
    }

    private void requireHubPlatformOnline(UUID hubId, String platform) {
        String connectionStatus = jdbcTemplate.query(
                "SELECT connection_status FROM hub_platforms WHERE hub_id = ? AND platform = ?",
                rs -> rs.next() ? rs.getString("connection_status") : null, hubId, platform);
        if (!"ONLINE".equals(connectionStatus)) {
            throw new HubNotOnlineException(hubId);
        }
    }
```

5. Delete the now-unused `HubStatusRow` record and its `requireHubOnlineAndGetPlatform` method.

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ExecutionServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/opshub/execution/application/ExecutionService.java \
        backend/src/test/java/com/opshub/execution/ExecutionServiceTest.java
git commit -m "feat: scope job dispatch per (hub, platform), drop the Web-worker auto-spawn"
```

---

### Task 4: Thread `platform` through the WebSocket and polling controllers

**Files:**
- Modify: `backend/src/main/java/com/opshub/hub/api/HubWebSocketHandler.java`
- Modify: `backend/src/main/java/com/opshub/hub/api/HubPollingController.java`
- Modify: `backend/src/test/java/com/opshub/hub/HubProtocolIT.java`

**Interfaces:**
- Consumes: `ExecutionService.offerNextJob(hubId, platform)`, `renewActiveLease(hubId, platform)` from Task 3; `HubConnectionService.markOffline(hubId, platform)` from Task 1.

- [ ] **Step 1: Write a new failing IT test proving concurrent Android + Web dispatch over the real endpoints**

Add to `HubProtocolIT.java` (needs a second helper creating a WEB-platform operation — copy `createApprovedOperation` and change the `official_accounts`/template-id insert to `platform = 'WEB'`, `template_id = 'web-template-' + order`, matching the `official_accounts` columns already used in `ExecutionServiceTest.approvePlan`):

```java
    @Test
    void oneHubIdCanHoldAnActiveAndroidLeaseAndAnActiveWebLeaseAtTheSameTime() throws Exception {
        UUID androidOperationId = createApprovedOperation("MOB-820");
        executionService.start(androidOperationId, 1, "concurrent-android-" + UUID.randomUUID());
        UUID webOperationId = createApprovedWebOperation("MOB-821");
        executionService.start(webOperationId, 1, "concurrent-web-" + UUID.randomUUID());
        UUID hubId = UUID.randomUUID();

        HttpHeaders androidHeaders = new HttpHeaders();
        androidHeaders.set("X-Hub-Token", TOKEN);
        androidHeaders.set("X-Hub-Platform", "ANDROID");
        ResponseEntity<HubEnvelopeV1> androidOffer = restTemplate.exchange(
                "http://localhost:{port}/api/v1/hubs/{hubId}/jobs/next?waitSeconds=5",
                HttpMethod.GET, new HttpEntity<>(androidHeaders), HubEnvelopeV1.class, port, hubId);
        assertThat(androidOffer.getStatusCode().value()).isEqualTo(200);

        HttpHeaders webHeaders = new HttpHeaders();
        webHeaders.set("X-Hub-Token", TOKEN);
        webHeaders.set("X-Hub-Platform", "WEB");
        ResponseEntity<HubEnvelopeV1> webOffer = restTemplate.exchange(
                "http://localhost:{port}/api/v1/hubs/{hubId}/jobs/next?waitSeconds=5",
                HttpMethod.GET, new HttpEntity<>(webHeaders), HubEnvelopeV1.class, port, hubId);
        assertThat(webOffer.getStatusCode().value()).isEqualTo(200);
    }

    private UUID createApprovedWebOperation(String jiraId) {
        UUID operationId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO operations (id, jira_id, revision, status, created_at, updated_at)
                        VALUES (?, ?, 1, 'DRAFT', now(), now())
                        """, operationId, jiraId);
        jdbcTemplate.update("""
                        INSERT INTO official_accounts (id, operation_id, oa_order, platform, oa_name, thumbnail_url, content, button_text, redirect_url)
                        VALUES (?, ?, 1, 'WEB', 'Test OA', 'https://example.test/thumb.png', 'content', 'Open', 'https://example.test/redirect')
                        """, UUID.randomUUID(), operationId);
        UUID planId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO test_plans (id, operation_id, source_revision, template_catalog_version, status, approval_status)
                        VALUES (?, ?, 1, 'web-v1', 'READY', 'APPROVED')
                        """, planId, operationId);
        for (int order = 1; order <= 5; order++) {
            jdbcTemplate.update("""
                            INSERT INTO test_cases (id, plan_id, oa_order, case_order, template_id, template_version, template_sha256, parameters, status)
                            VALUES (?, ?, 1, ?, ?, 1, 'sha', '{}', 'READY')
                            """, UUID.randomUUID(), planId, order, "web-template-" + order);
        }
        jdbcTemplate.update("""
                        UPDATE operations SET status = 'APPROVED', plan_id = ?, approved_plan_id = ? WHERE id = ?
                        """, planId, planId, operationId);
        return operationId;
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=HubProtocolIT`
Expected: FAIL — `HubPollingController.next` still calls the old `offerNextJob(hubId)` signature, so this won't even compile.

- [ ] **Step 3: Update `HubPollingController`**

```java
    @GetMapping("/jobs/next")
    public ResponseEntity<HubEnvelopeV1> next(@PathVariable UUID hubId,
                                               @RequestParam(defaultValue = "25") long waitSeconds,
                                               @RequestHeader("X-Hub-Token") String token,
                                               @RequestHeader(value = "X-Hub-Platform", defaultValue = "ANDROID") String platform)
            throws InterruptedException {
        requireValidToken(token);
        hubConnectionService.markOnline(hubId, "HTTPS_POLLING", platform);
        long capped = Math.min(waitSeconds, hubProperties.getPollWaitCapSeconds());
        Instant deadline = Instant.now().plusSeconds(capped);
        do {
            Optional<HubEnvelopeV1> offer = executionService.offerNextJob(hubId, platform);
            if (offer.isPresent()) {
                return ResponseEntity.ok(offer.get());
            }
            Thread.sleep(Math.min(POLL_INTERVAL.toMillis(), Math.max(0, Duration.between(Instant.now(), deadline).toMillis())));
        } while (Instant.now().isBefore(deadline));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@PathVariable UUID hubId, @RequestHeader("X-Hub-Token") String token,
                                           @RequestHeader(value = "X-Hub-Platform", defaultValue = "ANDROID") String platform,
                                           @RequestBody HubEnvelopeV1 envelope) {
        requireValidToken(token);
        var payload = new com.fasterxml.jackson.databind.ObjectMapper()
                .convertValue(envelope.payload(), com.opshub.hub.domain.HubPayloads.HeartbeatPayload.class);
        hubConnectionService.heartbeat(hubId, "HTTPS_POLLING", payload.deviceReady(), payload.runnerReady(), platform);
        executionService.renewActiveLease(hubId, platform);
        return ResponseEntity.ok().build();
    }
```

(The `/jobs/next` and `/heartbeat` method bodies are the only changes; the rest of the file — `renewLease`, `progress`, `results`, error handling — is unchanged.)

- [ ] **Step 4: Update `HubWebSocketHandler`**

```java
package com.opshub.hub.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opshub.execution.application.ExecutionService;
import com.opshub.hub.application.HubConnectionService;
import com.opshub.hub.domain.HubEnvelopeV1;
import com.opshub.hub.domain.HubPayloads;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class HubWebSocketHandler extends TextWebSocketHandler {
    static final String HUB_ID_ATTRIBUTE = "hubId";
    static final String HUB_PLATFORM_ATTRIBUTE = "hubPlatform";

    private final ExecutionService executionService;
    private final HubConnectionService hubConnectionService;
    private final ObjectMapper objectMapper;
    private final Map<HubPlatformKey, WebSocketSession> sessionsByHubPlatform = new ConcurrentHashMap<>();

    private record HubPlatformKey(UUID hubId, String platform) {
    }

    public HubWebSocketHandler(ExecutionService executionService, HubConnectionService hubConnectionService, ObjectMapper objectMapper) {
        this.executionService = executionService;
        this.hubConnectionService = hubConnectionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        UUID hubId = hubId(session);
        String platform = platform(session);
        sessionsByHubPlatform.put(new HubPlatformKey(hubId, platform), session);
        hubConnectionService.markOnline(hubId, "WEBSOCKET", platform);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        UUID hubId = hubId(session);
        String platform = platform(session);
        sessionsByHubPlatform.remove(new HubPlatformKey(hubId, platform), session);
        hubConnectionService.markOffline(hubId, platform);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        UUID hubId = hubId(session);
        String platform = platform(session);
        HubEnvelopeV1 envelope = objectMapper.readValue(message.getPayload(), HubEnvelopeV1.class);
        switch (envelope.type()) {
            case HubEnvelopeV1.TYPE_HEARTBEAT -> {
                HubPayloads.HeartbeatPayload payload = objectMapper.convertValue(envelope.payload(), HubPayloads.HeartbeatPayload.class);
                hubConnectionService.heartbeat(hubId, "WEBSOCKET", payload.deviceReady(), payload.runnerReady(), platform);
                executionService.renewActiveLease(hubId, platform);
                offerNextJobIfAny(hubId, platform, session);
            }
            case HubEnvelopeV1.TYPE_JOB_PROGRESS -> executionService.recordProgress(envelope);
            case HubEnvelopeV1.TYPE_TEST_RESULT -> {
                executionService.recordResult(envelope);
                offerNextJobIfAny(hubId, platform, session);
            }
            default -> throw new IllegalArgumentException("Unsupported message type: " + envelope.type());
        }
    }

    private void offerNextJobIfAny(UUID hubId, String platform, WebSocketSession session) throws IOException {
        Optional<HubEnvelopeV1> offer = executionService.offerNextJob(hubId, platform);
        if (offer.isPresent() && session.isOpen()) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(offer.get())));
        }
    }

    private UUID hubId(WebSocketSession session) {
        return (UUID) session.getAttributes().get(HUB_ID_ATTRIBUTE);
    }

    private String platform(WebSocketSession session) {
        return (String) session.getAttributes().get(HUB_PLATFORM_ATTRIBUTE);
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=HubProtocolIT,ExecutionServiceTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/opshub/hub/api/HubWebSocketHandler.java \
        backend/src/main/java/com/opshub/hub/api/HubPollingController.java \
        backend/src/test/java/com/opshub/hub/HubProtocolIT.java
git commit -m "feat: dispatch and track WebSocket sessions per (hub, platform)"
```

---

### Task 5: `HubQueryService` + `HubStatusController` multi-platform response

**Files:**
- Modify: `backend/src/main/java/com/opshub/hub/application/HubQueryService.java`
- Modify: `backend/src/test/java/com/opshub/hub/HubQueryServiceTest.java`
- Modify: `backend/src/main/java/com/opshub/hub/api/HubStatusController.java`
- Modify: `backend/src/test/java/com/opshub/hub/HubStatusControllerTest.java`

**Interfaces:**
- Produces: `HubQueryService.HubSummary(UUID id, String name, Instant createdAt, List<PlatformStatus> platforms)` and `HubQueryService.PlatformStatus(String platform, String connectionStatus, String transport, boolean deviceReady, boolean runnerReady, Instant lastHeartbeatAt)` — **replaces** the old flat-field `HubSummary` record. `HubStatusController.HubResponse` mirrors the same shape for JSON.

- [ ] **Step 1: Replace `HubQueryServiceTest`**

```java
package com.opshub.hub;

import com.opshub.hub.application.HubQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HubQueryServiceTest {

    @SuppressWarnings("unchecked")
    private static RowMapper<HubQueryService.HubPlatformRow> captureRowMapper(JdbcTemplate jdbcTemplate) {
        org.mockito.ArgumentCaptor<RowMapper<HubQueryService.HubPlatformRow>> captor =
                org.mockito.ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.query(anyString(), captor.capture())).thenReturn(List.of());
        new HubQueryService(jdbcTemplate, Clock.systemUTC()).listHubs();
        return captor.getValue();
    }

    private static ResultSet row(UUID hubId, String name, Instant createdAt, String platform,
                                  String connectionStatus, String transport, boolean deviceReady,
                                  boolean runnerReady, Instant lastHeartbeatAt) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(hubId);
        when(rs.getString("name")).thenReturn(name);
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(createdAt));
        when(rs.getString("platform")).thenReturn(platform);
        when(rs.getString("connection_status")).thenReturn(connectionStatus);
        when(rs.getString("transport")).thenReturn(transport);
        when(rs.getBoolean("device_ready")).thenReturn(deviceReady);
        when(rs.getBoolean("runner_ready")).thenReturn(runnerReady);
        when(rs.getTimestamp("last_heartbeat_at")).thenReturn(lastHeartbeatAt == null ? null : Timestamp.from(lastHeartbeatAt));
        return rs;
    }

    @Test
    void groupsMultiplePlatformRowsUnderOneHubSummary() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UUID hubId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-27T09:00:00Z");
        Instant heartbeatAt = Instant.parse("2026-07-28T10:00:00Z");

        ResultSet androidRow = row(hubId, "my-hub", createdAt, "ANDROID", "ONLINE", "WEBSOCKET", true, true, heartbeatAt);
        ResultSet webRow = row(hubId, "my-hub", createdAt, "WEB", "OFFLINE", "HTTPS_POLLING", false, false, null);

        RowMapper<HubQueryService.HubPlatformRow> mapper = captureRowMapper(jdbcTemplate);
        HubQueryService.HubPlatformRow mappedAndroid = mapper.mapRow(androidRow, 0);
        HubQueryService.HubPlatformRow mappedWeb = mapper.mapRow(webRow, 1);

        List<HubQueryService.HubSummary> summaries = HubQueryService.groupByHub(
                List.of(mappedAndroid, mappedWeb), Clock.fixed(heartbeatAt, ZoneOffset.UTC));

        assertThat(summaries).hasSize(1);
        HubQueryService.HubSummary hub = summaries.get(0);
        assertThat(hub.id()).isEqualTo(hubId);
        assertThat(hub.platforms()).hasSize(2);
        HubQueryService.PlatformStatus android = hub.platforms().stream()
                .filter(p -> p.platform().equals("ANDROID")).findFirst().orElseThrow();
        assertThat(android.connectionStatus()).isEqualTo("ONLINE");
        assertThat(android.deviceReady()).isTrue();
        HubQueryService.PlatformStatus web = hub.platforms().stream()
                .filter(p -> p.platform().equals("WEB")).findFirst().orElseThrow();
        assertThat(web.connectionStatus()).isEqualTo("OFFLINE");
        assertThat(web.lastHeartbeatAt()).isNull();
    }

    @Test
    void downgradesToOfflineWhenTheLastHeartbeatIsOlderThanTheStaleThreshold() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UUID hubId = UUID.randomUUID();
        Instant heartbeatAt = Instant.parse("2026-07-28T14:58:13Z");
        Instant now = heartbeatAt.plusSeconds(61);

        ResultSet androidRow = row(hubId, "hub", heartbeatAt, "ANDROID", "ONLINE", "WEBSOCKET", true, true, heartbeatAt);
        RowMapper<HubQueryService.HubPlatformRow> mapper = captureRowMapper(jdbcTemplate);
        HubQueryService.HubPlatformRow mapped = mapper.mapRow(androidRow, 0);

        List<HubQueryService.HubSummary> summaries = HubQueryService.groupByHub(
                List.of(mapped), Clock.fixed(now, ZoneOffset.UTC));

        assertThat(summaries.get(0).platforms().get(0).connectionStatus()).isEqualTo("OFFLINE");
    }

    @Test
    void ordersMostRecentlyCreatedHubsFirst() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        org.mockito.ArgumentCaptor<String> sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.query(sqlCaptor.capture(), any(RowMapper.class))).thenReturn(List.of());

        new HubQueryService(jdbcTemplate, Clock.systemUTC()).listHubs();

        String sql = sqlCaptor.getValue().toUpperCase();
        assertThat(sql).contains("ORDER BY");
        assertThat(sql).contains("CREATED_AT");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=HubQueryServiceTest`
Expected: FAIL — `HubQueryService.HubPlatformRow`/`groupByHub` don't exist yet.

- [ ] **Step 3: Rewrite `HubQueryService`**

```java
package com.opshub.hub.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read side of Hub connectivity state for the operator-facing UI. One Hub identity can now run
 * multiple platforms concurrently, so this groups the (hub, platform) rows in hub_platforms into
 * one HubSummary per hub_id with a list of per-platform statuses.
 */
@Service
public class HubQueryService {
    static final Duration STALE_AFTER = Duration.ofSeconds(60);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public HubQueryService(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public List<HubSummary> listHubs() {
        List<HubPlatformRow> rows = jdbcTemplate.query("""
                SELECT h.id, h.name, h.created_at, hp.platform, hp.connection_status, hp.transport,
                       hp.device_ready, hp.runner_ready, hp.last_heartbeat_at
                FROM hubs h
                LEFT JOIN hub_platforms hp ON hp.hub_id = h.id
                ORDER BY h.created_at DESC, hp.platform ASC
                """,
                (rs, rowNum) -> new HubPlatformRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("platform"),
                        rs.getString("connection_status"),
                        rs.getString("transport"),
                        rs.getBoolean("device_ready"),
                        rs.getBoolean("runner_ready"),
                        rs.getTimestamp("last_heartbeat_at") == null ? null : rs.getTimestamp("last_heartbeat_at").toInstant()));
        return groupByHub(rows, clock);
    }

    static List<HubSummary> groupByHub(List<HubPlatformRow> rows, Clock clock) {
        Map<UUID, List<HubPlatformRow>> byHub = new LinkedHashMap<>();
        for (HubPlatformRow row : rows) {
            byHub.computeIfAbsent(row.hubId(), id -> new ArrayList<>()).add(row);
        }
        List<HubSummary> summaries = new ArrayList<>();
        for (Map.Entry<UUID, List<HubPlatformRow>> entry : byHub.entrySet()) {
            List<HubPlatformRow> hubRows = entry.getValue();
            HubPlatformRow first = hubRows.get(0);
            List<PlatformStatus> platforms = new ArrayList<>();
            for (HubPlatformRow row : hubRows) {
                if (row.platform() == null) {
                    continue;
                }
                platforms.add(new PlatformStatus(row.platform(),
                        effectiveConnectionStatus(row.connectionStatus(), row.lastHeartbeatAt(), clock),
                        row.transport(), row.deviceReady(), row.runnerReady(), row.lastHeartbeatAt()));
            }
            summaries.add(new HubSummary(entry.getKey(), first.name(), first.createdAt(), platforms));
        }
        return summaries;
    }

    private static String effectiveConnectionStatus(String rawConnectionStatus, Instant lastHeartbeatAt, Clock clock) {
        if (!"ONLINE".equals(rawConnectionStatus)) {
            return rawConnectionStatus;
        }
        boolean stale = lastHeartbeatAt == null || Duration.between(lastHeartbeatAt, clock.instant()).compareTo(STALE_AFTER) > 0;
        return stale ? "OFFLINE" : "ONLINE";
    }

    /** One row of the hubs/hub_platforms LEFT JOIN, before grouping. */
    public record HubPlatformRow(
            UUID hubId, String name, Instant createdAt, String platform, String connectionStatus,
            String transport, boolean deviceReady, boolean runnerReady, Instant lastHeartbeatAt) {
    }

    public record PlatformStatus(
            String platform, String connectionStatus, String transport,
            boolean deviceReady, boolean runnerReady, Instant lastHeartbeatAt) {
    }

    public record HubSummary(UUID id, String name, Instant createdAt, List<PlatformStatus> platforms) {
    }
}
```

- [ ] **Step 4: Update `HubStatusController` and its test**

```java
package com.opshub.hub.api;

import com.opshub.hub.application.HubQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/hubs")
public class HubStatusController {
    private final HubQueryService hubQueryService;

    public HubStatusController(HubQueryService hubQueryService) {
        this.hubQueryService = hubQueryService;
    }

    @GetMapping
    public List<HubResponse> list() {
        return hubQueryService.listHubs().stream().map(HubResponse::from).toList();
    }

    public record HubResponse(UUID id, String name, Instant createdAt, List<PlatformResponse> platforms) {
        static HubResponse from(HubQueryService.HubSummary hub) {
            return new HubResponse(hub.id(), hub.name(), hub.createdAt(),
                    hub.platforms().stream().map(PlatformResponse::from).toList());
        }
    }

    public record PlatformResponse(
            String platform, String connectionStatus, String transport,
            boolean deviceReady, boolean runnerReady, Instant lastHeartbeatAt) {
        static PlatformResponse from(HubQueryService.PlatformStatus status) {
            return new PlatformResponse(status.platform(), status.connectionStatus(), status.transport(),
                    status.deviceReady(), status.runnerReady(), status.lastHeartbeatAt());
        }
    }
}
```

```java
// backend/src/test/java/com/opshub/hub/HubStatusControllerTest.java
package com.opshub.hub;

import com.opshub.hub.api.HubStatusController;
import com.opshub.hub.application.HubQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HubStatusController.class)
class HubStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HubQueryService hubQueryService;

    @Test
    void returnsOneHubWithBothPlatformsListed() throws Exception {
        UUID hubId = UUID.randomUUID();
        Instant heartbeatAt = Instant.parse("2026-07-28T13:47:10Z");
        Instant createdAt = Instant.parse("2026-07-27T15:21:09Z");
        when(hubQueryService.listHubs()).thenReturn(List.of(
                new HubQueryService.HubSummary(hubId, hubId.toString(), createdAt, List.of(
                        new HubQueryService.PlatformStatus("ANDROID", "ONLINE", "WEBSOCKET", true, true, heartbeatAt),
                        new HubQueryService.PlatformStatus("WEB", "OFFLINE", "HTTPS_POLLING", false, false, null)))));

        mockMvc.perform(get("/api/v1/hubs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(hubId.toString()))
                .andExpect(jsonPath("$[0].platforms[0].platform").value("ANDROID"))
                .andExpect(jsonPath("$[0].platforms[0].connectionStatus").value("ONLINE"))
                .andExpect(jsonPath("$[0].platforms[1].platform").value("WEB"))
                .andExpect(jsonPath("$[0].platforms[1].connectionStatus").value("OFFLINE"));
    }

    @Test
    void returnsAnEmptyArrayWhenNoHubHasEverConnected() throws Exception {
        when(hubQueryService.listHubs()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/hubs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }
}
```

- [ ] **Step 5: Run to verify everything passes**

Run: `cd backend && ./mvnw test -Dtest=HubQueryServiceTest,HubStatusControllerTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/opshub/hub/application/HubQueryService.java \
        backend/src/test/java/com/opshub/hub/HubQueryServiceTest.java \
        backend/src/main/java/com/opshub/hub/api/HubStatusController.java \
        backend/src/test/java/com/opshub/hub/HubStatusControllerTest.java
git commit -m "feat: report one Hub with a list of per-platform statuses to the operator UI"
```

---

### Task 6: Retire `WebWorkerLauncher`

**Files:**
- Delete: `backend/src/main/java/com/opshub/execution/application/WebWorkerLauncher.java`
- Delete: `backend/src/main/java/com/opshub/execution/application/WebWorkerProperties.java`
- Delete: `backend/src/test/java/com/opshub/execution/WebWorkerLauncherTest.java`

**Interfaces:**
- Consumes: nothing (already disconnected from `ExecutionService` since Task 3).

- [ ] **Step 1: Delete the three files**

```bash
git rm backend/src/main/java/com/opshub/execution/application/WebWorkerLauncher.java \
       backend/src/main/java/com/opshub/execution/application/WebWorkerProperties.java \
       backend/src/test/java/com/opshub/execution/WebWorkerLauncherTest.java
```

- [ ] **Step 2: Run the full backend test suite to confirm nothing else references them**

Run: `cd backend && ./mvnw test`
Expected: PASS — if this fails with a compile error naming `WebWorkerLauncher`/`WebWorkerProperties`, grep the failure for the file and remove that reference (it should not exist after Task 3's constructor change, but this is the safety check for that assumption).

- [ ] **Step 3: Commit**

```bash
git commit -m "chore: remove the Web-worker on-demand spawn, superseded by multi-platform Hub sessions"
```

---

### Task 7: Contracts — `HubSummary` schema

**Files:**
- Modify: `contracts/openapi/opshub-v1.yaml:325-351`

**Interfaces:**
- Produces: `HubSummary` schema matching `HubStatusController.HubResponse` from Task 5.

- [ ] **Step 1: Replace the `HubSummary` schema**

```yaml
    HubSummary:
      type: object
      required: [id, name, createdAt, platforms]
      properties:
        id:
          $ref: '#/components/schemas/Uuid'
        name:
          type: string
        createdAt:
          $ref: '#/components/schemas/Timestamp'
        platforms:
          type: array
          items:
            $ref: '#/components/schemas/HubPlatformStatus'
    HubPlatformStatus:
      type: object
      required: [platform, connectionStatus, transport, deviceReady, runnerReady]
      properties:
        platform:
          type: string
          enum: [ANDROID, WEB]
        connectionStatus:
          type: string
          enum: [ONLINE, OFFLINE]
        transport:
          type: string
          enum: [WEBSOCKET, HTTPS_POLLING]
        deviceReady:
          type: boolean
        runnerReady:
          type: boolean
        lastHeartbeatAt:
          anyOf:
            - $ref: '#/components/schemas/Timestamp'
            - type: 'null'
```

- [ ] **Step 2: Commit**

```bash
git add contracts/openapi/opshub-v1.yaml
git commit -m "docs: update HubSummary contract for one-hub-multiple-platforms shape"
```

---

### Task 8: Local Hub Python — `platforms` list + per-platform template roots

**Files:**
- Modify: `local-hub/src/opshub_hub/config.py`
- Modify: `local-hub/tests/test_config.py`

**Interfaces:**
- Produces: `HubConfig.platforms: tuple[Literal["ANDROID", "WEB"], ...]` (**replaces** `platform: Literal[...]`), `HubConfig.platform_template_root(platform: str) -> Path` (new method).

- [ ] **Step 1: Update `test_config.py`**

```python
import pytest

from opshub_hub.config import load_config


def _base_env(**overrides):
    env = {
        "OPSHUB_BACKEND_URL": "https://backend.example.test",
        "OPSHUB_HUB_ID": "hub-1",
        "OPSHUB_HUB_TOKEN": "token",
        "OPSHUB_TEMPLATE_DIR": "/tmp/templates",
        "OPSHUB_WORK_DIR": "/tmp/work",
        "OPSHUB_WDIO_PROJECT_DIR": "/tmp/wdio-project",
        "OPSHUB_NODE_EXECUTABLE": "/usr/bin/node",
    }
    env.update(overrides)
    return env


def test_platforms_defaults_to_android_only_when_unset():
    config = load_config(_base_env())
    assert config.platforms == ("ANDROID",)


def test_platforms_reads_a_comma_separated_list_from_env():
    config = load_config(_base_env(OPSHUB_PLATFORMS="ANDROID,WEB"))
    assert config.platforms == ("ANDROID", "WEB")


def test_platforms_tolerates_stray_whitespace_around_commas():
    config = load_config(_base_env(OPSHUB_PLATFORMS=" ANDROID , WEB "))
    assert config.platforms == ("ANDROID", "WEB")


def test_platform_template_root_derives_a_per_platform_subdirectory():
    config = load_config(_base_env(OPSHUB_TEMPLATE_DIR="/tmp/templates"))
    assert str(config.platform_template_root("ANDROID")) == "/tmp/templates/android"
    assert str(config.platform_template_root("WEB")) == "/tmp/templates/web"


def test_both_platforms_require_wdio_project_and_node_executable_env_vars():
    for platform_overrides in ({}, {"OPSHUB_PLATFORMS": "WEB"}):
        env = _base_env(**platform_overrides)
        del env["OPSHUB_WDIO_PROJECT_DIR"]
        del env["OPSHUB_NODE_EXECUTABLE"]

        with pytest.raises(ValueError, match="OPSHUB_WDIO_PROJECT_DIR"):
            load_config(env)
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd local-hub && python -m pytest tests/test_config.py -v`
Expected: FAIL — `config.platforms`/`platform_template_root` don't exist yet.

- [ ] **Step 3: Rewrite `config.py`**

```python
"""Typed Local Hub configuration.

Loaded from environment variables (see local-hub/.env.example). `platforms` lists which
platforms this Hub process runs concurrently, each in its own thread (see main.py) - a Hub
running ANDROID,WEB drives both an Android device and a Chrome profile from one process, one
session per platform, with no cross-platform interference. `template_root` is the *parent*
directory containing one subdirectory per platform (`android/`, `web/`); use
`platform_template_root(platform)` to get a specific platform's catalog root, never
`template_root` directly.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class HubConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    backend_url: str = Field(min_length=1)
    hub_id: str = Field(min_length=1)
    hub_token: str = Field(min_length=1)
    template_root: Path
    data_root: Path
    platforms: tuple[Literal["ANDROID", "WEB"], ...] = ("ANDROID",)
    wdio_project_root: Path
    node_executable: Path

    def platform_template_root(self, platform: str) -> Path:
        return self.template_root / platform.lower()

    @property
    def websocket_url(self) -> str:
        base = self.backend_url.rstrip("/")
        ws_base = base.replace("https://", "wss://").replace("http://", "ws://")
        return f"{ws_base}/ws/v1/hubs/{self.hub_id}"

    @property
    def poll_next_job_url(self) -> str:
        return f"{self.backend_url.rstrip('/')}/api/v1/hubs/{self.hub_id}/jobs/next"

    @property
    def heartbeat_url(self) -> str:
        return f"{self.backend_url.rstrip('/')}/api/v1/hubs/{self.hub_id}/heartbeat"

    def lease_renew_url(self, lease_token: str) -> str:
        return f"{self.backend_url.rstrip('/')}/api/v1/leases/{lease_token}/renew"

    @property
    def progress_url(self) -> str:
        return f"{self.backend_url.rstrip('/')}/api/v1/hubs/{self.hub_id}/progress"

    @property
    def results_url(self) -> str:
        return f"{self.backend_url.rstrip('/')}/api/v1/hubs/{self.hub_id}/results"


_ENV_MAP = {
    "backend_url": "OPSHUB_BACKEND_URL",
    "hub_id": "OPSHUB_HUB_ID",
    "hub_token": "OPSHUB_HUB_TOKEN",
    "template_root": "OPSHUB_TEMPLATE_DIR",
    "data_root": "OPSHUB_WORK_DIR",
    "wdio_project_root": "OPSHUB_WDIO_PROJECT_DIR",
    "node_executable": "OPSHUB_NODE_EXECUTABLE",
}


def load_config(env: dict | None = None) -> HubConfig:
    """Build a HubConfig from environment variables, raising if any are missing."""
    source = env if env is not None else os.environ
    missing = [name for name in _ENV_MAP.values() if not source.get(name)]
    if missing:
        raise ValueError(f"Missing required Local Hub environment variables: {', '.join(missing)}")
    platforms_raw = source.get("OPSHUB_PLATFORMS") or "ANDROID"
    platforms = tuple(p.strip() for p in platforms_raw.split(",") if p.strip())
    return HubConfig(
        backend_url=source[_ENV_MAP["backend_url"]],
        hub_id=source[_ENV_MAP["hub_id"]],
        hub_token=source[_ENV_MAP["hub_token"]],
        template_root=Path(source[_ENV_MAP["template_root"]]),
        data_root=Path(source[_ENV_MAP["data_root"]]),
        platforms=platforms,
        wdio_project_root=Path(source[_ENV_MAP["wdio_project_root"]]),
        node_executable=Path(source[_ENV_MAP["node_executable"]]),
    )
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd local-hub && python -m pytest tests/test_config.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add local-hub/src/opshub_hub/config.py local-hub/tests/test_config.py
git commit -m "feat: run a Local Hub against a list of platforms, not a single fixed one"
```

---

### Task 9: Local Hub Python — per-platform transport headers

**Files:**
- Modify: `local-hub/src/opshub_hub/transport/websocket_client.py`
- Modify: `local-hub/src/opshub_hub/transport/polling_client.py`
- Modify: `local-hub/tests/test_polling_client.py`

**Interfaces:**
- Consumes: `HubConfig` from Task 8 (no longer has `.platform`).
- Produces: `WebSocketTransport(config: HubConfig, platform: str, connect_timeout: float = 10.0)`, `PollingTransport(config: HubConfig, platform: str, http_client: httpx.Client | None = None)` — both gain an explicit `platform` parameter instead of reading `config.platform`.

- [ ] **Step 1: Update `test_polling_client.py`**

```python
"""Covers the distinction PollingTransport.send draws between a transient
failure (5xx/network - should be retried) and a permanent rejection (4xx, e.g.
409 MESSAGE_OUT_OF_ORDER - retrying the same envelope will never succeed)."""

from pathlib import Path

import httpx
import pytest

from opshub_hub.config import HubConfig
from opshub_hub.transport import PermanentTransportError, TransportError
from opshub_hub.transport.polling_client import PollingTransport


def make_config() -> HubConfig:
    return HubConfig(
        backend_url="http://backend.local",
        hub_id="hub-1",
        hub_token="secret-hub-token",
        template_root=Path("/tmp/templates"),
        data_root=Path("/tmp/data"),
        wdio_project_root=Path("/tmp/wdio-project"),
        node_executable=Path("/usr/bin/node"),
    )


def transport_with_handler(handler) -> PollingTransport:
    client = httpx.Client(transport=httpx.MockTransport(handler))
    return PollingTransport(make_config(), "ANDROID", http_client=client)


def test_send_raises_permanent_error_on_409_out_of_order():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(409, json={"code": "MESSAGE_OUT_OF_ORDER", "message": "stale"})

    transport = transport_with_handler(handler)

    with pytest.raises(PermanentTransportError):
        transport.send({"messageId": "1", "type": "TEST_RESULT"})


def test_send_raises_transient_error_on_500():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="boom")

    transport = transport_with_handler(handler)

    with pytest.raises(TransportError) as exc_info:
        transport.send({"messageId": "1", "type": "TEST_RESULT"})
    assert not isinstance(exc_info.value, PermanentTransportError)


def test_heartbeat_sends_the_platform_this_transport_was_built_for():
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["platform_header"] = request.headers.get("x-hub-platform")
        return httpx.Response(200)

    client = httpx.Client(transport=httpx.MockTransport(handler))
    transport = PollingTransport(make_config(), "WEB", http_client=client)

    transport.heartbeat()

    assert captured["platform_header"] == "WEB"
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd local-hub && python -m pytest tests/test_polling_client.py -v`
Expected: FAIL — `PollingTransport(make_config(), "ANDROID", http_client=client)` doesn't match the current `(config, http_client=None)` signature, and `make_config()` no longer includes a `platform=` kwarg the current `HubConfig` still accepts positionally-incompatible with the new required `platforms` field name (harmless — `platforms` defaults, so this still constructs fine).

- [ ] **Step 3: Update `polling_client.py`**

Change the constructor and `_headers`:

```python
class PollingTransport:
    def __init__(self, config: HubConfig, platform: str, http_client: httpx.Client | None = None):
        self._config = config
        self._platform = platform
        self._client = http_client or httpx.Client(timeout=WAIT_SECONDS + 10)
```

```python
    def _headers(self) -> dict:
        return {"X-Hub-Token": self._config.hub_token, "X-Hub-Platform": self._platform}
```

(Every other method is unchanged.)

- [ ] **Step 4: Update `websocket_client.py`**

```python
class WebSocketTransport:
    def __init__(self, config: HubConfig, platform: str, connect_timeout: float = 10.0):
        self._config = config
        self._platform = platform
        self._connect_timeout = connect_timeout
        self._connection: ClientConnection | None = None

    def connect(self) -> None:
        try:
            self._connection = connect(
                self._config.websocket_url,
                additional_headers={"X-Hub-Token": self._config.hub_token, "X-Hub-Platform": self._platform},
                open_timeout=self._connect_timeout,
            )
        except Exception as exc:  # noqa: BLE001 - any connect failure is a transport failure
            self._connection = None
            raise TransportError(f"WebSocket connect failed: {exc}") from exc
```

(Every other method is unchanged.)

- [ ] **Step 5: Run to verify it passes**

Run: `cd local-hub && python -m pytest tests/test_polling_client.py tests/test_transport_failover.py -v`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add local-hub/src/opshub_hub/transport/websocket_client.py \
        local-hub/src/opshub_hub/transport/polling_client.py \
        local-hub/tests/test_polling_client.py
git commit -m "feat: pass platform explicitly to Hub transports instead of reading a shared config field"
```

---

### Task 10: Local Hub Python — concurrent per-platform threads in `main.py`

**Files:**
- Modify: `local-hub/src/opshub_hub/main.py`
- Modify: `local-hub/tests/test_main.py`

**Interfaces:**
- Consumes: `HubConfig.platforms`/`platform_template_root` from Task 8, `WebSocketTransport(config, platform)`/`PollingTransport(config, platform)` from Task 9.
- Produces: `build_runner(config, transport, outbox)` and `build_web_runner(config, transport, outbox)` (**existing names/signatures unchanged**, but internally now read `config.platform_template_root("ANDROID"/"WEB")` instead of `config.template_root`); `run_forever(config=None)` now runs one thread per `config.platforms` entry instead of exactly one runner loop.

- [ ] **Step 1: Update `test_main.py`'s config construction for the new per-platform template root**

The existing tests build a `HubConfig` with `template_root=TEMPLATE_ROOT` pointed directly at `local-hub/templates/android` or `.../web` — since `template_root` is now the *parent* of those, and `build_runner`/`build_web_runner` derive the per-platform path themselves, update both test configs to point at the shared parent instead:

```python
TEMPLATE_ROOT = Path(__file__).resolve().parents[1] / "templates"
```

(Single change at the top of the file — replace the existing `TEMPLATE_ROOT` line, which pointed at `.../ templates" / "android"`.) Then in `test_build_web_runner_uses_the_pinned_wdio_web_conf_and_screenshot_capturer`, change:

```python
        template_root=Path(__file__).resolve().parents[1] / "templates" / "web",
```

to:

```python
        template_root=Path(__file__).resolve().parents[1] / "templates",
```

Also change that same test's `platform="WEB"` kwarg to `platforms=("WEB",)` (the field was renamed in Task 8).

Add a new test proving concurrent platform isolation:

```python
def test_run_platform_logs_and_returns_without_raising_when_preflight_fails(tmp_path, caplog):
    from opshub_hub.main import _run_platform

    config = HubConfig(
        backend_url="https://backend.example.test",
        hub_id="hub-1",
        hub_token="token",
        template_root=tmp_path / "nonexistent-templates",
        data_root=tmp_path,
        platforms=("ANDROID",),
        wdio_project_root=tmp_path / "nonexistent-wdio-project",
        node_executable=Path("/usr/bin/node"),
    )

    with caplog.at_level("ERROR"):
        _run_platform(config, "ANDROID")

    assert any("Preflight" in record.message for record in caplog.records), (
        "a failed preflight must log and return, not raise - one platform's broken environment "
        "must not crash the whole multi-platform Hub process"
    )
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd local-hub && python -m pytest tests/test_main.py -v`
Expected: FAIL — `_run_platform` doesn't exist yet, and `build_web_runner`/`build_runner` still read `config.template_root` directly (wrong path once `test_main.py`'s `TEMPLATE_ROOT` points at the parent).

- [ ] **Step 3: Rewrite `main.py`**

```python
"""Local Hub entrypoint: wires config, transports, journal, outbox, template
catalog, and the Runner together for each configured platform, running them
concurrently - one thread per platform, each fully isolated from the others.
"""

from __future__ import annotations

import contextlib
import logging
import threading
import time

from opshub_hub.appium_control import AdbScreenshotCapturer, AppiumSessionResetter
from opshub_hub.browser_control import WebScreenshotCapturer
from opshub_hub.config import HubConfig, load_config
from opshub_hub.evidence import HttpEvidenceUploader
from opshub_hub.journal import ExecutionJournal
from opshub_hub.models import JobOfferedPayload
from opshub_hub.outbox import Outbox
from opshub_hub.preflight import run_preflight, run_web_preflight
from opshub_hub.runner import Runner, build_wdio_command_builder
from opshub_hub.templates import TemplateCatalog
from opshub_hub.transport.failover import FailoverTransport
from opshub_hub.transport.polling_client import PollingTransport
from opshub_hub.transport.websocket_client import WebSocketTransport

logger = logging.getLogger("opshub_hub")


def build_runner(config: HubConfig, transport: FailoverTransport, outbox: Outbox) -> Runner:
    catalog = TemplateCatalog(config.platform_template_root("ANDROID"))
    execution_root = config.data_root / "executions"
    evidence_uploader = HttpEvidenceUploader(base_url=config.backend_url, hub_token=config.hub_token)
    command_builder = build_wdio_command_builder(config.node_executable, config.wdio_project_root)
    return Runner(
        catalog=catalog,
        execution_root=execution_root,
        launcher=_SubprocessLauncherImpl(),
        outbox=outbox,
        transport=transport,
        evidence_uploader=evidence_uploader,
        screenshot_capturer=AdbScreenshotCapturer(),
        reset_appium_session=AppiumSessionResetter(),
        command_builder=command_builder,
        wdio_project_root=config.wdio_project_root,
    )


def build_web_runner(config: HubConfig, transport: FailoverTransport, outbox: Outbox) -> Runner:
    catalog = TemplateCatalog(config.platform_template_root("WEB"))
    execution_root = config.data_root / "executions"
    evidence_uploader = HttpEvidenceUploader(base_url=config.backend_url, hub_token=config.hub_token)
    command_builder = build_wdio_command_builder(
        config.node_executable, config.wdio_project_root, config_filename="wdio.web.conf.ts"
    )
    return Runner(
        catalog=catalog,
        execution_root=execution_root,
        launcher=_SubprocessLauncherImpl(),
        outbox=outbox,
        transport=transport,
        evidence_uploader=evidence_uploader,
        screenshot_capturer=WebScreenshotCapturer(),
        reset_appium_session=None,
        command_builder=command_builder,
        wdio_project_root=config.wdio_project_root,
        wdio_config_filename="wdio.web.conf.ts",
    )


class _SubprocessLauncherImpl:
    """Real subprocess launcher used outside of tests."""

    def run(self, command, cwd, timeout):
        import subprocess

        from opshub_hub.runner import ProcessResult

        try:
            completed = subprocess.run(
                command, cwd=cwd, capture_output=True, text=True, timeout=timeout
            )
            return ProcessResult(returncode=completed.returncode, stdout=completed.stdout, stderr=completed.stderr)
        except subprocess.TimeoutExpired as exc:
            return ProcessResult(
                returncode=-1,
                stdout=exc.stdout or "",
                stderr=f"{exc.stderr or ''}\nTimed out after {timeout}s waiting for the spec to finish.",
                timed_out=True,
            )


HEARTBEAT_INTERVAL_SECONDS = 20.0


@contextlib.contextmanager
def _heartbeat_while_running(transport: FailoverTransport, interval: float = HEARTBEAT_INTERVAL_SECONDS):
    """Keeps sending heartbeats on a background thread for the duration of the `with` block."""
    stop = threading.Event()

    def _beat() -> None:
        while not stop.wait(interval):
            try:
                transport.heartbeat()
            except Exception:
                logger.warning("Heartbeat failed while a job was running; will retry.", exc_info=True)

    thread = threading.Thread(target=_beat, name="opshub-hub-job-heartbeat", daemon=True)
    thread.start()
    try:
        yield
    finally:
        stop.set()
        thread.join(timeout=interval)


def _run_platform(config: HubConfig, platform: str) -> None:
    """Runs one platform's full preflight-connect-loop pipeline to completion (or until the
    process is killed). Isolated per platform: a failure here does not affect any other
    platform thread running in the same process."""
    if platform == "WEB":
        chrome_profile_dir = config.data_root / "chrome-profile"
        preflight = run_web_preflight(
            template_root=config.platform_template_root("WEB"),
            data_root=config.data_root,
            chrome_profile_dir=chrome_profile_dir,
            required_executables=(str(config.node_executable),),
            wdio_project_root=config.wdio_project_root,
        )
    else:
        preflight = run_preflight(
            template_root=config.platform_template_root("ANDROID"),
            data_root=config.data_root,
            required_executables=(str(config.node_executable), "adb"),
            wdio_project_root=config.wdio_project_root,
        )
    if not preflight.ok:
        for failure in preflight.failures():
            logger.error("[%s] Preflight check failed: %s (%s)", platform, failure.name, failure.detail)
        logger.error("[%s] Preflight checks failed; this platform will not run in this Hub process.", platform)
        return

    journal = ExecutionJournal(config.data_root / f"journal-{platform.lower()}.sqlite3")
    outbox = Outbox(config.data_root / f"outbox-{platform.lower()}.sqlite3")
    transport = FailoverTransport(
        ws_transport=WebSocketTransport(config, platform),
        polling_transport=PollingTransport(config, platform),
    )
    transport.ws_transport.connect()

    runner = build_web_runner(config, transport, outbox) if platform == "WEB" else build_runner(config, transport, outbox)

    while True:
        outbox.flush(transport)
        job = transport.receive_job()
        if job is None:
            transport.heartbeat()
            time.sleep(1.0)
            continue
        try:
            payload = JobOfferedPayload.model_validate(job.get("payload", job))
        except Exception:
            logger.exception("[%s] Rejected an invalid JOB_OFFERED payload from the backend; skipping it.", platform)
            continue
        if not journal.claim(str(payload.executionId), payload.idempotencyKey):
            continue
        with _heartbeat_while_running(transport):
            runner.run(payload)
        journal.complete(str(payload.executionId))


def run_forever(config: HubConfig | None = None) -> None:
    config = config or load_config()

    threads = [
        threading.Thread(target=_run_platform, args=(config, platform), name=f"opshub-hub-{platform.lower()}", daemon=False)
        for platform in config.platforms
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    run_forever()
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd local-hub && python -m pytest tests/test_main.py -v`
Expected: PASS

- [ ] **Step 5: Run the full Local Hub test suite**

Run: `cd local-hub && python -m pytest -v`
Expected: PASS (this also catches anything in `test_transport_failover.py`, `test_journal.py`, etc. affected by earlier tasks)

- [ ] **Step 6: Commit**

```bash
git add local-hub/src/opshub_hub/main.py local-hub/tests/test_main.py
git commit -m "feat: run one thread per configured platform, replacing the single-runner loop"
```

---

### Task 11: Frontend — multi-platform `HubStatusIndicator`

**Files:**
- Modify: `frontend/src/api/generated.ts:216-227`
- Modify: `frontend/src/components/HubStatusIndicator.tsx`
- Modify: `frontend/src/components/HubStatusIndicator.test.tsx`
- Modify: `frontend/src/components/HubStatusIndicator.module.css` (only if a new row needs a distinguishing class — see Step 3)

**Interfaces:**
- Consumes: the `platforms` array shape from Task 5/7.
- Produces: `HubStatusIndicator` renders one row per platform under one Hub identity.

- [ ] **Step 1: Update the `HubSummary` type**

```typescript
// frontend/src/api/generated.ts

/** One platform's connectivity/readiness state within a Hub. */
export interface HubPlatformStatus {
  platform: Platform;
  connectionStatus: "ONLINE" | "OFFLINE";
  transport: "WEBSOCKET" | "HTTPS_POLLING";
  deviceReady: boolean;
  runnerReady: boolean;
  lastHeartbeatAt: string | null;
}

/** One row from GET /api/v1/hubs, ordered most-recently-created first. */
export interface HubSummary {
  id: Uuid;
  name: string;
  createdAt: string;
  platforms: HubPlatformStatus[];
}
```

- [ ] **Step 2: Rewrite `HubStatusIndicator.test.tsx`**

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { HubStatusIndicator } from "./HubStatusIndicator";
import type { HubSummary, HubPlatformStatus } from "../api/generated";

const mocks = vi.hoisted(() => ({
  listHubs: vi.fn(),
}));

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof import("../api/client")>("../api/client");
  return {
    ...actual,
    apiClient: mocks,
  };
});

function platformStatus(overrides: Partial<HubPlatformStatus> = {}): HubPlatformStatus {
  return {
    platform: "ANDROID",
    connectionStatus: "ONLINE",
    transport: "WEBSOCKET",
    deviceReady: true,
    runnerReady: true,
    lastHeartbeatAt: "2026-07-28T13:47:10Z",
    ...overrides,
  };
}

function hub(overrides: Partial<HubSummary> = {}): HubSummary {
  return {
    id: "3c75ce1d-e42d-4f16-b20f-b358df58a175",
    name: "3c75ce1d-e42d-4f16-b20f-b358df58a175",
    createdAt: "2026-07-27T15:21:09Z",
    platforms: [platformStatus()],
    ...overrides,
  };
}

function renderIndicator() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <HubStatusIndicator />
    </QueryClientProvider>,
  );
}

describe("HubStatusIndicator", () => {
  it("shows an online indicator when at least one platform is online", async () => {
    mocks.listHubs.mockResolvedValue([hub()]);
    renderIndicator();

    await waitFor(() => expect(screen.getByRole("status")).toHaveAccessibleName(/online/i));
    expect(screen.getByText("3c75ce1d-e42d-4f16-b20f-b358df58a175")).toBeInTheDocument();
    expect(screen.getByText("ANDROID")).toBeInTheDocument();
  });

  it("shows one row per platform under the same hub", async () => {
    mocks.listHubs.mockResolvedValue([
      hub({
        platforms: [
          platformStatus({ platform: "ANDROID", connectionStatus: "ONLINE" }),
          platformStatus({ platform: "WEB", connectionStatus: "OFFLINE" }),
        ],
      }),
    ]);
    renderIndicator();

    await waitFor(() => expect(screen.getByText("ANDROID")).toBeInTheDocument());
    expect(screen.getByText("WEB")).toBeInTheDocument();
  });

  it("shows an offline indicator when every platform is offline", async () => {
    mocks.listHubs.mockResolvedValue([hub({ platforms: [platformStatus({ connectionStatus: "OFFLINE" })] })]);
    renderIndicator();

    await waitFor(() => expect(screen.getByRole("status")).toHaveAccessibleName(/offline/i));
  });

  it("shows a 'no hub' state when none has ever connected", async () => {
    mocks.listHubs.mockResolvedValue([]);
    renderIndicator();

    await waitFor(() => expect(screen.getByRole("status")).toHaveAccessibleName(/no hub/i));
  });
});
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd frontend && npx vitest run src/components/HubStatusIndicator.test.tsx`
Expected: FAIL — `HubStatusIndicator` still reads flat `hub.platform`/`hub.deviceReady` fields that no longer exist on `HubSummary`.

- [ ] **Step 4: Rewrite `HubStatusIndicator.tsx`**

```tsx
import type { ReactElement } from "react";
import { useQuery } from "@tanstack/react-query";
import { apiClient } from "../api/client";
import type { HubPlatformStatus, HubSummary } from "../api/generated";
import styles from "./HubStatusIndicator.module.css";

const POLL_INTERVAL_MS = 10_000;

function formatHeartbeat(lastHeartbeatAt: string | null): string {
  if (!lastHeartbeatAt) {
    return "Never";
  }
  const seconds = Math.max(0, Math.round((Date.now() - new Date(lastHeartbeatAt).getTime()) / 1000));
  if (seconds < 60) {
    return `${seconds}s ago`;
  }
  const minutes = Math.round(seconds / 60);
  return `${minutes}m ago`;
}

function PlatformRow({ status }: { status: HubPlatformStatus }): ReactElement {
  return (
    <div className={styles.platformBlock}>
      <div className={styles.row}>
        <span className={styles.rowLabel}>Platform</span>
        <span className={styles.rowValue}>{status.platform}</span>
      </div>
      <div className={styles.row}>
        <span className={styles.rowLabel}>Status</span>
        <span className={styles.rowValue}>{status.connectionStatus}</span>
      </div>
      <div className={styles.row}>
        <span className={styles.rowLabel}>Session</span>
        <span className={styles.rowValue}>{status.transport}</span>
      </div>
      <div className={styles.row}>
        <span className={styles.rowLabel}>Device</span>
        <span className={styles.rowValue}>{status.deviceReady ? "Ready" : "Not ready"}</span>
      </div>
      <div className={styles.row}>
        <span className={styles.rowLabel}>Runner</span>
        <span className={styles.rowValue}>{status.runnerReady ? "Ready" : "Not ready"}</span>
      </div>
      <div className={styles.row}>
        <span className={styles.rowLabel}>Last heartbeat</span>
        <span className={styles.rowValue}>{formatHeartbeat(status.lastHeartbeatAt)}</span>
      </div>
    </div>
  );
}

export function HubStatusIndicator(): ReactElement {
  const { data: hubs } = useQuery({
    queryKey: ["hubs"],
    queryFn: () => apiClient.listHubs(),
    refetchInterval: POLL_INTERVAL_MS,
  });

  const hub: HubSummary | undefined = hubs?.[0];
  const isOnline = hub?.platforms.some((platform) => platform.connectionStatus === "ONLINE") ?? false;
  const dotClass = !hubs ? styles.unknown : hub && isOnline ? styles.online : styles.offline;
  const accessibleName = !hubs ? "Hub status unknown" : hub ? (isOnline ? "Hub online" : "Hub offline") : "No hub has ever connected";

  return (
    <div className={styles.wrapper} tabIndex={0}>
      <span className={`${styles.dot} ${dotClass}`} role="status" aria-label={accessibleName} />
      <div className={styles.panel}>
        <p className={styles.title}>Local Hub</p>
        {hub ? (
          <>
            <div className={styles.row}>
              <span className={styles.rowLabel}>Hub ID</span>
              <span className={styles.rowValue}>{hub.id}</span>
            </div>
            {hub.platforms.map((status) => (
              <PlatformRow key={status.platform} status={status} />
            ))}
          </>
        ) : (
          <div className={styles.row}>
            <span className={styles.rowLabel}>No hub has ever connected</span>
          </div>
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 5: Add the `platformBlock` class**

Read `frontend/src/components/HubStatusIndicator.module.css` first, then append a rule visually separating each platform's block of rows, e.g.:

```css
.platformBlock {
  padding-top: var(--ops-space-2);
  margin-top: var(--ops-space-2);
  border-top: 1px solid var(--ops-color-gray-200);
}
```

- [ ] **Step 6: Run to verify it passes**

Run: `cd frontend && npx vitest run src/components/HubStatusIndicator.test.tsx`
Expected: PASS

- [ ] **Step 7: Run the full frontend test suite and type check**

Run: `cd frontend && npx vitest run && npx tsc --noEmit`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add frontend/src/api/generated.ts \
        frontend/src/components/HubStatusIndicator.tsx \
        frontend/src/components/HubStatusIndicator.test.tsx \
        frontend/src/components/HubStatusIndicator.module.css
git commit -m "feat: show one Hub with a status row per platform in the operator UI"
```

---

## Self-Review Notes

- **Spec coverage:** data model (Task 1), lease scoping (Task 2), dispatch/WS/polling (Tasks 3-4), operator read side (Task 5), Web-worker retirement (Task 6), contracts (Task 7), Local Hub config/transports/concurrency (Tasks 8-10), frontend (Task 11) — every section of the design spec maps to a task.
- **Placeholder scan:** no TBD/TODO; every step includes complete, real code derived from the actual current files read during planning.
- **Type consistency:** `offerNextJob(hubId, platform)` / `renewActiveLease(hubId, platform)` / `hasActiveLease(hubId, platform)` / `acquire(hubId, platform, executionId)` names and parameter order are used identically across Tasks 2-4; `platform_template_root` name matches between Task 8's definition and Task 10's usage; `HubSummary.platforms` / `PlatformStatus` field names match across Tasks 5, 7, and 11.
