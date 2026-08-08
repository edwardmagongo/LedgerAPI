# Multi-instance Concurrency, Observability, and Chaos Testing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove LedgerAPI's optimistic-locking concurrency guarantee holds across separate
application instances (not just threads in one JVM), make that story observable via
Prometheus/Grafana, and prove an instance dying mid-transfer fails visibly rather than
corrupting the ledger.

**Architecture:** Additive to the existing single-service LedgerAPI. Locally,
`docker-compose` gains three named app instances behind no new load balancer (a script
round-robins directly), plus Prometheus and Grafana. On AWS, the existing ALB already
load-balances across ECS tasks — scaling is a `desired_count` change applied outside
Terraform (which is configured to ignore that field), via a small script.

**Tech Stack:** Java 21, Spring Boot 3.5.3, Micrometer + `micrometer-registry-prometheus`,
Docker Compose (profiles), Prometheus, Grafana, Node.js 18+ (verification scripts, same
pattern as the existing `scripts/loadtest.mjs`), Terraform (unchanged), AWS CLI.

## Global Constraints

- Java 21 / Spring Boot 3.5.3 — matches the existing `pom.xml`, do not bump either.
- The existing 145 JUnit tests must continue to pass unmodified in behavior; only their
  setup code changes where a constructor signature changes.
- New verification scripts require Node 18+ (built-in `fetch`), matching
  `scripts/loadtest.mjs`. They are **not** part of `./mvnw test` — they require the
  multi-container `docker-compose` stack running, the same reason the existing load test
  isn't a Maven-run test either.
- All application containers sharing one Postgres must use the **same**
  `LEDGER_JWT_SECRET`, or tokens issued by one instance won't validate on another.
- No new load-balancer component for local development — verification scripts round-robin
  across known ports directly.
- No database replication/failover — explicitly out of scope (see spec's Non-goals).
- Metric names are exactly `ledger.transfer.retry.count`, `ledger.transfer.duration`, and
  `ledger.idempotency.replay.count`, per the approved spec.

---

### Task 1: Expose Prometheus metrics endpoint

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/edwardmagongo/ledgerapi/OpenApiDocsTest.java` (reference for the
  existing pattern this task's new test follows)
- Create: `src/test/java/com/edwardmagongo/ledgerapi/PrometheusEndpointTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `/actuator/prometheus` returns `200` with `Content-Type` starting
  `text/plain` once this task lands — later tasks' custom metrics appear on this endpoint
  automatically once registered.

- [ ] **Step 1: Add the Prometheus registry dependency**

In `pom.xml`, add this dependency inside the existing `<dependencies>` block, immediately
after the `spring-boot-starter-actuator` dependency (currently lines 43-46):

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
```

- [ ] **Step 2: Expose the prometheus endpoint**

In `src/main/resources/application.yml`, change:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
```

to:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
```

- [ ] **Step 3: Write the failing test**

Create `src/test/java/com/edwardmagongo/ledgerapi/PrometheusEndpointTest.java`:

```java
package com.edwardmagongo.ledgerapi;

import com.edwardmagongo.ledgerapi.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusEndpointTest extends AbstractIntegrationTest {

    @Autowired TestRestTemplate rest;

    @Test
    void prometheusEndpointIsExposedAndReturnsTextFormat() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).contains("text/plain");
    }
}
```

- [ ] **Step 4: Run the test to verify it currently fails**

Run: `./mvnw -q test -Dtest=PrometheusEndpointTest`
Expected: FAIL — 404, since `prometheus` isn't in the exposure list yet (before Step 2) or
the dependency isn't present yet (before Step 1). Run this before Steps 1-2 if working
strictly TDD, or immediately confirm it now passes if Steps 1-2 are already applied above.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=PrometheusEndpointTest`
Expected: PASS

- [ ] **Step 6: Run the full suite to confirm nothing broke**

Run: `./mvnw -q test`
Expected: `Tests run: 146, Failures: 0, Errors: 0` (145 existing + 1 new)

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/resources/application.yml src/test/java/com/edwardmagongo/ledgerapi/PrometheusEndpointTest.java
git commit -m "feat: expose /actuator/prometheus metrics endpoint"
```

---

### Task 2: Retry and duration metrics on ConflictRetry

**Files:**
- Modify: `src/main/java/com/edwardmagongo/ledgerapi/common/ConflictRetry.java`
- Modify: `src/test/java/com/edwardmagongo/ledgerapi/common/ConflictRetryTest.java`
- Modify: `src/test/java/com/edwardmagongo/ledgerapi/transfer/TransferServiceTest.java`

**Interfaces:**
- Consumes: `io.micrometer.core.instrument.MeterRegistry` (Spring-provided bean once Task 1
  is applied; a `new SimpleMeterRegistry()` in tests).
- Produces: `ConflictRetry(MeterRegistry registry)` — the constructor signature changes
  from no-arg to this. Every other class already depends on `ConflictRetry` only through
  Spring autowiring except the two test files listed above, which construct it directly.

**Why here, not in `TransferService`:** `ConflictRetry` is the single chokepoint every
conflict-guarded operation goes through — transfers, deposits, *and* withdrawals (see
`TransactionService`, which also depends on it) — so this is where a
`ledger.transfer.retry.count` metric captures all of them uniformly, without touching
`TransferService`'s constructor or its test.

- [ ] **Step 1: Write the failing tests**

In `src/test/java/com/edwardmagongo/ledgerapi/common/ConflictRetryTest.java`, replace the
top of the file (imports and the `retry` field) from:

```java
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConflictRetryTest {

    private final ConflictRetry retry = new ConflictRetry();
```

to:

```java
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConflictRetryTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ConflictRetry retry = new ConflictRetry(meterRegistry);
```

Then add these two new test methods at the end of the class, immediately before the final
closing `}`:

```java

    @Test
    void recordsARetriedMetricForEachConflictAndADurationOnSuccess() {
        AtomicInteger calls = new AtomicInteger();

        retry.execute(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new ObjectOptimisticLockingFailureException("Account", "id");
            }
            return "ok";
        });

        assertThat(meterRegistry.get("ledger.transfer.retry.count").tag("outcome", "retried")
                .counter().count()).isEqualTo(2.0);
        assertThat(meterRegistry.get("ledger.transfer.duration").timer().count()).isEqualTo(1);
    }

    @Test
    void recordsAnExhaustedMetricAndADurationWhenAttemptsRunOut() {
        assertThatThrownBy(() -> retry.execute(() -> {
            throw new ObjectOptimisticLockingFailureException("Account", "id");
        })).isInstanceOf(WriteConflictException.class);

        assertThat(meterRegistry.get("ledger.transfer.retry.count").tag("outcome", "exhausted")
                .counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("ledger.transfer.retry.count").tag("outcome", "retried")
                .counter().count()).isEqualTo(6.0);
        assertThat(meterRegistry.get("ledger.transfer.duration").timer().count()).isEqualTo(1);
    }
```

In `src/test/java/com/edwardmagongo/ledgerapi/transfer/TransferServiceTest.java`, change:

```java
import com.edwardmagongo.ledgerapi.common.ConflictRetry;
```

to:

```java
import com.edwardmagongo.ledgerapi.common.ConflictRetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
```

and change:

```java
    // The real ConflictRetry is used, not a mock: the retry behaviour is exactly what is under test.
    private final ConflictRetry conflictRetry = new ConflictRetry();
```

to:

```java
    // The real ConflictRetry is used, not a mock: the retry behaviour is exactly what is under test.
    private final ConflictRetry conflictRetry = new ConflictRetry(new SimpleMeterRegistry());
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `./mvnw -q test -Dtest=ConflictRetryTest`
Expected: FAIL to compile — `ConflictRetry(MeterRegistry)` constructor doesn't exist yet.

- [ ] **Step 3: Implement the metrics in ConflictRetry**

Replace the full contents of
`src/main/java/com/edwardmagongo/ledgerapi/common/ConflictRetry.java`:

```java
package com.edwardmagongo.ledgerapi.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Runs an operation, retrying only write-conflict failures.
 *
 * <p>This must always be invoked from <em>outside</em> a transaction, wrapping a call to a
 * separate {@code @Transactional} bean. An optimistic-lock failure surfaces at flush/commit —
 * that is, as the transactional method exits — so a retry inside that method would never see it,
 * and the transaction would already be rollback-only. Each retry here starts a genuinely fresh
 * transaction with a fresh persistence context and freshly read entity state.
 */
@Component
public class ConflictRetry {

    private static final Logger log = LoggerFactory.getLogger(ConflictRetry.class);
    private static final int MAX_ATTEMPTS = 7;
    private static final long BASE_BACKOFF_MILLIS = 25;
    private static final long MAX_BACKOFF_MILLIS = 400;

    // These fire for every conflict-guarded operation - transfers, deposits, and withdrawals all
    // go through this class - not transfers alone, despite the metric name.
    private final Counter retriedCounter;
    private final Counter exhaustedCounter;
    private final Timer operationTimer;

    public ConflictRetry(MeterRegistry registry) {
        this.retriedCounter = Counter.builder("ledger.transfer.retry.count")
                .tag("outcome", "retried")
                .description("Optimistic-lock or deadlock conflicts that triggered a retry")
                .register(registry);
        this.exhaustedCounter = Counter.builder("ledger.transfer.retry.count")
                .tag("outcome", "exhausted")
                .description("Conflicts that exhausted all retry attempts")
                .register(registry);
        this.operationTimer = Timer.builder("ledger.transfer.duration")
                .description("End-to-end duration of a conflict-guarded operation, including any retries")
                .publishPercentileHistogram()
                .register(registry);
    }

    public <T> T execute(Supplier<T> operation) {
        Timer.Sample sample = Timer.start();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                T result = operation.get();
                sample.stop(operationTimer);
                return result;
            } catch (ObjectOptimisticLockingFailureException | CannotAcquireLockException ex) {
                if (attempt == MAX_ATTEMPTS) {
                    exhaustedCounter.increment();
                    sample.stop(operationTimer);
                    log.warn("Write conflict persisted after {} attempts", MAX_ATTEMPTS);
                    throw new WriteConflictException();
                }
                retriedCounter.increment();
                log.debug("Write conflict on attempt {}, retrying", attempt);
                backoff(attempt);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    // Full jitter: sleep uniformly at random within [0, cap), where cap grows exponentially per
    // attempt (capped at MAX_BACKOFF_MILLIS), so competing threads decorrelate far more effectively
    // than a narrow fixed-width jitter band added on top of a fixed per-attempt floor.
    private void backoff(int attempt) {
        long cap = Math.min(MAX_BACKOFF_MILLIS, BASE_BACKOFF_MILLIS * (1L << attempt));
        long sleepMillis = ThreadLocalRandom.current().nextLong(cap);
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new WriteConflictException();
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest=ConflictRetryTest,TransferServiceTest`
Expected: PASS — all existing tests in both classes still pass (constructor call sites
updated, behavior unchanged), plus the 2 new metric tests pass.

- [ ] **Step 5: Run the full suite**

Run: `./mvnw -q test`
Expected: `Tests run: 148, Failures: 0, Errors: 0` (146 from Task 1 + 2 new)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/edwardmagongo/ledgerapi/common/ConflictRetry.java \
        src/test/java/com/edwardmagongo/ledgerapi/common/ConflictRetryTest.java \
        src/test/java/com/edwardmagongo/ledgerapi/transfer/TransferServiceTest.java
git commit -m "feat: record retry, exhaustion, and duration metrics in ConflictRetry"
```

---

### Task 3: Idempotency replay metric

**Files:**
- Modify: `src/main/java/com/edwardmagongo/ledgerapi/common/idempotency/IdempotencyService.java`
- Modify: `src/test/java/com/edwardmagongo/ledgerapi/common/idempotency/IdempotencyServiceTest.java`

**Interfaces:**
- Consumes: `io.micrometer.core.instrument.MeterRegistry`.
- Produces: `IdempotencyService(IdempotencyClaims, IdempotentOperationExecutor, ConflictRetry, MeterRegistry)`
  — constructor gains a 4th parameter. No other class constructs `IdempotencyService`
  directly (verified: only Spring autowires it in production, and the test file below is
  the only direct instantiation).

- [ ] **Step 1: Write the failing test and update test setup**

In `src/test/java/com/edwardmagongo/ledgerapi/common/idempotency/IdempotencyServiceTest.java`,
change the imports from:

```java
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
```

to:

```java
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
```

(`InjectMocks` is no longer used — `MeterRegistry` needs a real, cheap in-memory registry,
not a mock, or the `Counter` built from it at construction time would be built against a
mock that returns `null` from `register()`, causing an NPE on first use.)

Change:

```java
    @Mock IdempotencyClaims claims;
    @Mock IdempotentOperationExecutor executor;
    @Mock ConflictRetry conflictRetry;

    @InjectMocks IdempotencyService service;

    private UUID userId;
    private UUID claimId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        claimId = UUID.randomUUID();
    }
```

to:

```java
    @Mock IdempotencyClaims claims;
    @Mock IdempotentOperationExecutor executor;
    @Mock ConflictRetry conflictRetry;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private IdempotencyService service;

    private UUID userId;
    private UUID claimId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        claimId = UUID.randomUUID();
        service = new IdempotencyService(claims, executor, conflictRetry, meterRegistry);
    }
```

Then add this new test at the end of the class, immediately before the final closing `}`:

```java

    @Test
    void aSuccessfulReplayIncrementsTheReplayMetric() {
        when(claims.claim(userId, "k", IdempotentOperation.TRANSFER, "fp"))
                .thenReturn(Optional.empty());
        when(claims.find(userId, "k"))
                .thenReturn(Optional.of(completedKey("fp", 201, "{\"replayed\":true}")));

        service.execute(userId, "k", IdempotentOperation.TRANSFER, "fp", () -> "must not run");

        assertThat(meterRegistry.get("ledger.idempotency.replay.count").counter().count())
                .isEqualTo(1.0);
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q test -Dtest=IdempotencyServiceTest`
Expected: FAIL to compile — `IdempotencyService` doesn't accept a 4th constructor argument
yet.

- [ ] **Step 3: Implement the metric in IdempotencyService**

In `src/main/java/com/edwardmagongo/ledgerapi/common/idempotency/IdempotencyService.java`,
change:

```java
import com.edwardmagongo.ledgerapi.common.BlankIdempotencyKeyException;
import com.edwardmagongo.ledgerapi.common.ConflictRetry;
import com.edwardmagongo.ledgerapi.common.IdempotencyKeyInFlightException;
import com.edwardmagongo.ledgerapi.common.IdempotencyKeyReusedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
```

to:

```java
import com.edwardmagongo.ledgerapi.common.BlankIdempotencyKeyException;
import com.edwardmagongo.ledgerapi.common.ConflictRetry;
import com.edwardmagongo.ledgerapi.common.IdempotencyKeyInFlightException;
import com.edwardmagongo.ledgerapi.common.IdempotencyKeyReusedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
```

Change:

```java
    private final IdempotencyClaims claims;
    private final IdempotentOperationExecutor executor;
    private final ConflictRetry conflictRetry;

    public IdempotencyService(IdempotencyClaims claims, IdempotentOperationExecutor executor,
                              ConflictRetry conflictRetry) {
        this.claims = claims;
        this.executor = executor;
        this.conflictRetry = conflictRetry;
    }
```

to:

```java
    private final IdempotencyClaims claims;
    private final IdempotentOperationExecutor executor;
    private final ConflictRetry conflictRetry;
    private final Counter replayCounter;

    public IdempotencyService(IdempotencyClaims claims, IdempotentOperationExecutor executor,
                              ConflictRetry conflictRetry, MeterRegistry registry) {
        this.claims = claims;
        this.executor = executor;
        this.conflictRetry = conflictRetry;
        this.replayCounter = Counter.builder("ledger.idempotency.replay.count")
                .description("Requests served from a stored idempotent response instead of executed")
                .register(registry);
    }
```

Change:

```java
        if (!existing.isCompleted()) {
            throw new IdempotencyKeyInFlightException();
        }
        return new IdempotentOutcome.Replayed<>(existing.getResponseStatus(), existing.getResponseBody());
    }
```

to:

```java
        if (!existing.isCompleted()) {
            throw new IdempotencyKeyInFlightException();
        }
        replayCounter.increment();
        return new IdempotentOutcome.Replayed<>(existing.getResponseStatus(), existing.getResponseBody());
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest=IdempotencyServiceTest`
Expected: PASS — all existing tests plus the new metric test.

- [ ] **Step 5: Run the full suite**

Run: `./mvnw -q test`
Expected: `Tests run: 149, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/edwardmagongo/ledgerapi/common/idempotency/IdempotencyService.java \
        src/test/java/com/edwardmagongo/ledgerapi/common/idempotency/IdempotencyServiceTest.java
git commit -m "feat: record idempotency replay metric"
```

---

### Task 4: Prometheus and Grafana in docker-compose

**Files:**
- Modify: `docker-compose.yml`
- Create: `observability/prometheus.yml`
- Create: `observability/grafana-datasource.yml`
- Create: `observability/grafana-dashboard-provisioning.yml`
- Create: `observability/grafana-dashboard.json`

**Interfaces:**
- Consumes: `/actuator/prometheus` on each app instance (Tasks 1-3).
- Produces: Prometheus reachable at `localhost:9090`, Grafana at `localhost:3000` with the
  "LedgerAPI" dashboard pre-provisioned (no manual setup) once `docker compose up` includes
  these services.

- [ ] **Step 1: Add the Prometheus scrape config**

Create `observability/prometheus.yml`:

```yaml
global:
  scrape_interval: 5s

scrape_configs:
  - job_name: ledger-api
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["api:8080"]
        labels:
          instance_name: single
      - targets: ["app1:8080"]
        labels:
          instance_name: app1
      - targets: ["app2:8080"]
        labels:
          instance_name: app2
      - targets: ["app3:8080"]
        labels:
          instance_name: app3
```

Targets `app1`/`app2`/`app3` only resolve once the `multi` Compose profile is running
(Task 6) — until then, Prometheus reports them as `down`, which is expected and harmless.

- [ ] **Step 2: Add the Grafana datasource provisioning**

Create `observability/grafana-datasource.yml`:

```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
```

- [ ] **Step 3: Add the Grafana dashboard provisioning config**

Create `observability/grafana-dashboard-provisioning.yml`:

```yaml
apiVersion: 1
providers:
  - name: ledger-api
    folder: ""
    type: file
    options:
      path: /var/lib/grafana/dashboards
```

- [ ] **Step 4: Add the dashboard itself**

Create `observability/grafana-dashboard.json`:

```json
{
  "title": "LedgerAPI",
  "uid": "ledger-api",
  "schemaVersion": 39,
  "refresh": "5s",
  "time": { "from": "now-15m", "to": "now" },
  "panels": [
    {
      "id": 1,
      "title": "Conflict retries / sec",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 0 },
      "targets": [
        {
          "expr": "sum(rate(ledger_transfer_retry_count_total{outcome=\"retried\"}[1m]))",
          "legendFormat": "retried"
        },
        {
          "expr": "sum(rate(ledger_transfer_retry_count_total{outcome=\"exhausted\"}[1m]))",
          "legendFormat": "exhausted"
        }
      ]
    },
    {
      "id": 2,
      "title": "Idempotency replays / sec",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 0 },
      "targets": [
        {
          "expr": "sum(rate(ledger_idempotency_replay_count_total[1m]))",
          "legendFormat": "replays"
        }
      ]
    },
    {
      "id": 3,
      "title": "Conflict-guarded operation latency (p50/p95/p99)",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 24, "x": 0, "y": 8 },
      "targets": [
        {
          "expr": "histogram_quantile(0.50, sum(rate(ledger_transfer_duration_seconds_bucket[1m])) by (le))",
          "legendFormat": "p50"
        },
        {
          "expr": "histogram_quantile(0.95, sum(rate(ledger_transfer_duration_seconds_bucket[1m])) by (le))",
          "legendFormat": "p95"
        },
        {
          "expr": "histogram_quantile(0.99, sum(rate(ledger_transfer_duration_seconds_bucket[1m])) by (le))",
          "legendFormat": "p99"
        }
      ]
    }
  ]
}
```

- [ ] **Step 5: Wire Prometheus and Grafana into docker-compose.yml**

In `docker-compose.yml`, add these two services after the existing `api` service (before
the closing `volumes:` block):

```yaml
  prometheus:
    image: prom/prometheus:v2.53.0
    volumes:
      - ./observability/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    ports:
      - "9090:9090"

  grafana:
    image: grafana/grafana:11.1.0
    environment:
      GF_AUTH_ANONYMOUS_ENABLED: "true"
      GF_AUTH_ANONYMOUS_ORG_ROLE: Admin
    volumes:
      - ./observability/grafana-datasource.yml:/etc/grafana/provisioning/datasources/datasource.yml:ro
      - ./observability/grafana-dashboard-provisioning.yml:/etc/grafana/provisioning/dashboards/provisioning.yml:ro
      - ./observability/grafana-dashboard.json:/var/lib/grafana/dashboards/ledger-api.json:ro
    ports:
      - "3000:3000"
```

- [ ] **Step 6: Verify it starts and scrapes**

Run: `docker compose up -d --build`

Then: `curl -s http://localhost:9090/api/v1/targets | grep -o '"health":"[a-z]*"'`
Expected: at least one `"health":"up"` (the `single` / `api:8080` target — `app1`/`app2`/
`app3` show as down until Task 6, which is expected).

Then open `http://localhost:3000` in a browser — the "LedgerAPI" dashboard should be
visible under Dashboards without any manual login or setup (anonymous admin access is
enabled for local dev only, via `GF_AUTH_ANONYMOUS_ENABLED`).

Run: `docker compose down`

- [ ] **Step 7: Commit**

```bash
git add docker-compose.yml observability/
git commit -m "feat: add Prometheus and Grafana to the local compose stack"
```

---

### Task 5: README — Observability section

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: nothing (documentation only).
- Produces: nothing consumed by later tasks, but establishes the section-insertion point
  ("after Concurrency safety") that Tasks 8 and 10 continue from.

- [ ] **Step 1: Insert the Observability section and update the Table of Contents**

In `README.md`, change the Table of Contents from:

```markdown
- [Concurrency safety](#concurrency-safety)
- [Tests](#tests)
```

to:

```markdown
- [Concurrency safety](#concurrency-safety)
- [Observability](#observability)
- [Tests](#tests)
```

Then find the end of the existing "Concurrency safety" section — it ends with this
paragraph (currently the last content before `## Tests`):

```markdown
`SELECT ... FOR UPDATE` is simpler — no retry loop, no lost-update window. It was rejected
because it serializes every writer on an account row for the whole read-validate-write window,
which hurts throughput under contention. Optimistic locking trades that for retry logic and a
small chance of a client-visible `409` under heavy single-account contention. For a ledger where
contention on any one account is normally low, that is the better trade. Under sustained
contention on a single hot account, pessimistic locking would be the better choice.

## Tests
```

Replace it with:

```markdown
`SELECT ... FOR UPDATE` is simpler — no retry loop, no lost-update window. It was rejected
because it serializes every writer on an account row for the whole read-validate-write window,
which hurts throughput under contention. Optimistic locking trades that for retry logic and a
small chance of a client-visible `409` under heavy single-account contention. For a ledger where
contention on any one account is normally low, that is the better trade. Under sustained
contention on a single hot account, pessimistic locking would be the better choice.

## Observability

`/actuator/prometheus` exposes Micrometer metrics, including three tied directly to the
concurrency story above:

| Metric | Type | What it shows |
|---|---|---|
| `ledger.transfer.retry.count{outcome="retried"}` | counter | Optimistic-lock/deadlock conflicts that triggered a retry |
| `ledger.transfer.retry.count{outcome="exhausted"}` | counter | Conflicts that exhausted all 7 attempts and surfaced as a `409` |
| `ledger.idempotency.replay.count` | counter | Requests served from a stored response instead of executed |
| `ledger.transfer.duration` | histogram | End-to-end duration of a conflict-guarded operation, including retries |

```bash
docker compose up -d --build
```

brings up Prometheus (`localhost:9090`) and Grafana (`localhost:3000`, anonymous admin
access for local dev) alongside the API, with a "LedgerAPI" dashboard already provisioned
— no manual setup. Run [`scripts/loadtest.mjs`](scripts/loadtest.mjs) or
[`scripts/multiinstance-test.mjs`](scripts/multiinstance-test.mjs) against it and watch the
retry-rate panel move in real time as contention happens.

## Tests
```

- [ ] **Step 2: Verify the README renders sensibly**

Run: `grep -c "^## " README.md`
Expected: one more heading than before this task (confirms the new `## Observability`
heading was added, not a malformed edit).

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: add Observability section"
```

---

### Task 6: Multi-instance docker-compose topology

**Files:**
- Modify: `docker-compose.yml`

**Interfaces:**
- Consumes: nothing new (reuses the existing `api` service's build/env pattern).
- Produces: three named, independently reachable containers — `ledger-app1` on
  `localhost:8081`, `ledger-app2` on `localhost:8082`, `ledger-app3` on `localhost:8083` —
  which Tasks 7 and 9's scripts connect to by these exact ports and container names.

- [ ] **Step 1: Add the three-instance profile**

In `docker-compose.yml`, add these three services after the existing `api` service
(the `prometheus`/`grafana` services from Task 4 can come before or after — order among
services doesn't matter to Compose):

```yaml
  app1:
    build: .
    container_name: ledger-app1
    profiles: ["multi"]
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/ledger
      SPRING_DATASOURCE_USERNAME: ledger
      SPRING_DATASOURCE_PASSWORD: ledger
      # Must match app2 and app3 exactly: a token issued by one instance has to validate on
      # the others, since the whole point is that any instance can serve any request.
      LEDGER_JWT_SECRET: local-compose-secret-change-me-0123456789abcdef0123456789
    ports:
      - "8081:8080"

  app2:
    build: .
    container_name: ledger-app2
    profiles: ["multi"]
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/ledger
      SPRING_DATASOURCE_USERNAME: ledger
      SPRING_DATASOURCE_PASSWORD: ledger
      LEDGER_JWT_SECRET: local-compose-secret-change-me-0123456789abcdef0123456789
    ports:
      - "8082:8080"

  app3:
    build: .
    container_name: ledger-app3
    profiles: ["multi"]
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/ledger
      SPRING_DATASOURCE_USERNAME: ledger
      SPRING_DATASOURCE_PASSWORD: ledger
      LEDGER_JWT_SECRET: local-compose-secret-change-me-0123456789abcdef0123456789
    ports:
      - "8083:8080"
```

`profiles: ["multi"]` means these three do **not** start on a plain `docker compose up` —
the existing single-instance `api` quickstart in the README is unaffected.

- [ ] **Step 2: Verify the default (single-instance) flow is unaffected**

Run: `docker compose up -d --build`
Run: `docker compose ps --format '{{.Name}}'`
Expected: only `postgres`, `api`, `prometheus`, `grafana` — no `app1`/`app2`/`app3`.
Run: `docker compose down`

- [ ] **Step 3: Verify the multi-instance profile starts all three**

Run: `docker compose --profile multi up -d --build postgres app1 app2 app3`
Run: `docker compose ps --format '{{.Name}}'`
Expected: `postgres`, `ledger-app1`, `ledger-app2`, `ledger-app3`.

Run: `curl -s http://localhost:8081/actuator/health && curl -s http://localhost:8082/actuator/health && curl -s http://localhost:8083/actuator/health`
Expected: three `{"status":"UP"}` responses (allow ~30-60s after start for Spring Boot
context startup, per the existing health-check grace-period note elsewhere in this repo).

Run: `docker compose --profile multi down`

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml
git commit -m "feat: add three-instance 'multi' compose profile"
```

---

### Task 7: Multi-instance concurrency verification script

**Files:**
- Create: `scripts/multiinstance-test.mjs`

**Interfaces:**
- Consumes: `LEDGER_API_INSTANCES` env var (comma-separated base URLs, defaults to the
  three ports from Task 6); the same `/api/auth/*`, `/api/accounts*`, `/api/transfers`
  endpoints `scripts/loadtest.mjs` already uses.
- Produces: a script runnable via `node scripts/multiinstance-test.mjs`, exit code `0` on
  success and non-zero (via the thrown `Error`) on failure — matching `loadtest.mjs`'s
  convention.

- [ ] **Step 1: Write the script**

Create `scripts/multiinstance-test.mjs`:

```js
// Proves TransferConcurrencyTest's guarantee holds across separate processes, not just threads in
// one JVM: fires the same contended-transfer scenarios via HTTP, round-robining requests across
// three independently-running LedgerAPI instances sharing one Postgres, then reconciles balances
// from the transaction log exactly as the JUnit test does.
//
// Usage:
//   docker compose --profile multi up -d --build postgres app1 app2 app3
//   node scripts/multiinstance-test.mjs
//
// Requires Node 18+ (built-in fetch). Point it at different instances with LEDGER_API_INSTANCES
// (comma-separated base URLs).

const INSTANCES = (
  process.env.LEDGER_API_INSTANCES || "http://localhost:8081,http://localhost:8082,http://localhost:8083"
).split(",");

let nextInstance = 0;
function pickInstance() {
  const url = INSTANCES[nextInstance % INSTANCES.length];
  nextInstance++;
  return url;
}

async function timedFetch(path, opts) {
  const base = pickInstance();
  const start = performance.now();
  const res = await fetch(`${base}${path}`, opts);
  const body = await res.text();
  return { status: res.status, body, ms: performance.now() - start, instance: base };
}

async function runAcrossInstances(total, requestFn) {
  return Promise.all(Array.from({ length: total }, (_, i) => requestFn(i)));
}

async function register() {
  const email = `multiinstance-${Date.now()}-${Math.random().toString(36).slice(2)}@example.com`;
  const password = "multiinstance-password-123";

  let r = await timedFetch("/api/auth/register", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (r.status !== 201) throw new Error(`register failed: ${r.status} ${r.body}`);

  r = await timedFetch("/api/auth/login", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (r.status !== 200) throw new Error(`login failed: ${r.status} ${r.body}`);
  const { token } = JSON.parse(r.body);
  return { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };
}

async function openAccount(auth, opening) {
  let r = await timedFetch("/api/accounts", { method: "POST", headers: auth, body: JSON.stringify({ currency: "GBP" }) });
  const account = JSON.parse(r.body);
  if (parseFloat(opening) > 0) {
    r = await timedFetch(`/api/accounts/${account.id}/deposit`, {
      method: "POST", headers: auth, body: JSON.stringify({ amount: opening }),
    });
    if (r.status !== 201) throw new Error(`seed deposit failed: ${r.status} ${r.body}`);
  }
  return account;
}

async function reconcile(auth, accountId) {
  let page = 0, all = [];
  while (true) {
    const r = await timedFetch(`/api/accounts/${accountId}/transactions?page=${page}&size=100`, { headers: auth });
    const body = JSON.parse(r.body);
    all = all.concat(body.content);
    if (page >= body.totalPages - 1) break;
    page++;
  }
  let balance = 0;
  for (const t of all) {
    const amt = parseFloat(t.amount);
    balance += (t.type === "DEPOSIT" || t.type === "TRANSFER_IN") ? amt : -amt;
  }
  const stored = JSON.parse((await timedFetch(`/api/accounts/${accountId}`, { headers: auth })).body).balance;
  return {
    computed: balance.toFixed(2),
    stored,
    txnCount: all.length,
    matches: balance.toFixed(2) === parseFloat(stored).toFixed(2),
  };
}

async function main() {
  console.log(`Round-robining across ${INSTANCES.length} instances: ${INSTANCES.join(", ")}`);
  const auth = await register();

  // --- Scenario 1: 20-way contention out of one account, requests spread across instances ---
  {
    const source = await openAccount(auth, "1000.00");
    const destination = await openAccount(auth, "0.00");

    const results = await runAcrossInstances(20, () =>
      timedFetch("/api/transfers", {
        method: "POST", headers: auth,
        body: JSON.stringify({ fromAccountId: source.id, toAccountId: destination.id, amount: "10.00" }),
      })
    );

    const byInstance = {};
    for (const r of results) byInstance[r.instance] = (byInstance[r.instance] || 0) + 1;
    const succeeded = results.filter((r) => r.status === 201).length;

    console.log("\n=== Scenario 1: 20-way contention on one account, across instances ===");
    console.log(`requests per instance: ${JSON.stringify(byInstance)}`);
    console.log(`succeeded: ${succeeded}/20`);

    const sourceRecon = await reconcile(auth, source.id);
    const destRecon = await reconcile(auth, destination.id);
    console.log(`source reconciles: ${sourceRecon.matches} (computed=${sourceRecon.computed}, stored=${sourceRecon.stored})`);
    console.log(`destination reconciles: ${destRecon.matches} (computed=${destRecon.computed}, stored=${destRecon.stored})`);

    if (succeeded !== 20 || !sourceRecon.matches || !destRecon.matches) {
      throw new Error("Scenario 1 FAILED: balances did not reconcile across instances");
    }
  }

  // --- Scenario 2: 20 concurrent overdraft attempts against a balance that only covers 5 ---
  {
    const source = await openAccount(auth, "50.00");
    const destination = await openAccount(auth, "0.00");

    const results = await runAcrossInstances(20, () =>
      timedFetch("/api/transfers", {
        method: "POST", headers: auth,
        body: JSON.stringify({ fromAccountId: source.id, toAccountId: destination.id, amount: "10.00" }),
      })
    );

    const succeeded = results.filter((r) => r.status === 201).length;
    console.log("\n=== Scenario 2: overdraft-limited contention, across instances ===");
    console.log(`succeeded: ${succeeded}/20 (expected exactly 5)`);

    const sourceRecon = await reconcile(auth, source.id);
    console.log(`source reconciles: ${sourceRecon.matches} (computed=${sourceRecon.computed}, stored=${sourceRecon.stored})`);

    if (succeeded !== 5 || !sourceRecon.matches || parseFloat(sourceRecon.stored) < 0) {
      throw new Error("Scenario 2 FAILED: overdraft protection or reconciliation broke across instances");
    }
  }

  console.log("\nAll multi-instance concurrency scenarios passed.");
}

main().catch((e) => { console.error(e); process.exit(1); });
```

- [ ] **Step 2: Run it against the multi-instance stack**

Run: `docker compose --profile multi up -d --build postgres app1 app2 app3`

Wait ~60s for all three to report healthy (`curl -s http://localhost:8081/actuator/health`
etc., as in Task 6 Step 3), then:

Run: `node scripts/multiinstance-test.mjs`
Expected: both scenarios print `reconciles: true`, ending with `All multi-instance
concurrency scenarios passed.` and exit code `0` (verify with `echo $?`).

Run: `docker compose --profile multi down`

- [ ] **Step 3: Commit**

```bash
git add scripts/multiinstance-test.mjs
git commit -m "feat: add multi-instance concurrency verification script"
```

---

### Task 8: README — Multi-instance concurrency section

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: nothing (documentation only).

- [ ] **Step 1: Insert the section and update the Table of Contents**

In `README.md`, change:

```markdown
- [Concurrency safety](#concurrency-safety)
- [Observability](#observability)
- [Tests](#tests)
```

to:

```markdown
- [Concurrency safety](#concurrency-safety)
- [Multi-instance concurrency](#multi-instance-concurrency)
- [Observability](#observability)
- [Tests](#tests)
```

Then, immediately after the `## Observability` section added in Task 5 and before
`## Tests`, insert:

```markdown
## Multi-instance concurrency

`TransferConcurrencyTest` proves the locking holds across threads in one JVM. That's not
the same claim as correctness across separate **processes**, which is what actually
happens once this is scaled out — a real deployment doesn't guarantee two concurrent
requests land on the same instance.

```bash
docker compose --profile multi up -d --build postgres app1 app2 app3
node scripts/multiinstance-test.mjs
```

[`scripts/multiinstance-test.mjs`](scripts/multiinstance-test.mjs) runs the same contended
scenarios `TransferConcurrencyTest` runs in-JVM — 20-way contention on one account, and 20
concurrent overdraft attempts against a balance that only covers 5 — but round-robins each
request across three independently-running instances over HTTP, then reconciles balances
from the transaction log exactly as the JUnit test does. This has to be a script rather
than a JUnit test: `@SpringBootTest` starts one application per test JVM, so proving
cross-process correctness needs actually-separate running processes.
```

- [ ] **Step 2: Verify the README renders sensibly**

Run: `grep -c "^## " README.md`
Expected: one more heading than after Task 5.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: add Multi-instance concurrency section"
```

---

### Task 9: Chaos test script

**Files:**
- Create: `scripts/chaos-test.mjs`

**Interfaces:**
- Consumes: `LEDGER_API_INSTANCES`, `LEDGER_KILL_CONTAINER` (defaults to `ledger-app2`,
  matching Task 6's `container_name`), `LEDGER_KILL_AFTER_MS` (defaults to `300`); the
  `docker kill` CLI (via Node's `child_process.execSync`).
- Produces: a script runnable via `node scripts/chaos-test.mjs`, same exit-code convention
  as Task 7's script.

- [ ] **Step 1: Write the script**

Create `scripts/chaos-test.mjs`:

```js
// Chaos test: fires concurrent transfer load across three instances, kills one mid-run, and proves
// the ledger never corrupts even though requests in flight to the killed instance fail visibly.
//
// What this does NOT prove: that no individual request ever fails. A request being processed on
// the instance at the exact moment it dies has no way to complete - there is no distributed
// transaction between the load balancer and the app. What it proves is narrower and more important:
// after the dust settles, the ledger balance still reconciles exactly with its transaction log.
//
// Usage:
//   docker compose --profile multi up -d --build postgres app1 app2 app3
//   node scripts/chaos-test.mjs
//
// Requires Node 18+ (built-in fetch) and the docker CLI. Restart the killed container afterward:
//   docker compose --profile multi up -d ledger-app2

import { execSync } from "node:child_process";

const INSTANCES = (
  process.env.LEDGER_API_INSTANCES || "http://localhost:8081,http://localhost:8082,http://localhost:8083"
).split(",");
const KILL_CONTAINER = process.env.LEDGER_KILL_CONTAINER || "ledger-app2";
const KILL_AFTER_MS = Number(process.env.LEDGER_KILL_AFTER_MS || 300);

let nextInstance = 0;
function pickInstance() {
  const url = INSTANCES[nextInstance % INSTANCES.length];
  nextInstance++;
  return url;
}

async function timedFetch(path, opts) {
  const base = pickInstance();
  const start = performance.now();
  try {
    const res = await fetch(`${base}${path}`, opts);
    const body = await res.text();
    return { status: res.status, body, ms: performance.now() - start, instance: base, failed: false };
  } catch (err) {
    return { status: null, body: String(err), ms: performance.now() - start, instance: base, failed: true };
  }
}

async function register() {
  const email = `chaos-${Date.now()}-${Math.random().toString(36).slice(2)}@example.com`;
  const password = "chaos-password-123";
  let r = await timedFetch("/api/auth/register", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (r.status !== 201) throw new Error(`register failed: ${r.status} ${r.body}`);
  r = await timedFetch("/api/auth/login", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (r.status !== 200) throw new Error(`login failed: ${r.status} ${r.body}`);
  const { token } = JSON.parse(r.body);
  return { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };
}

async function openAccount(auth, opening) {
  let r = await timedFetch("/api/accounts", { method: "POST", headers: auth, body: JSON.stringify({ currency: "GBP" }) });
  const account = JSON.parse(r.body);
  if (parseFloat(opening) > 0) {
    r = await timedFetch(`/api/accounts/${account.id}/deposit`, {
      method: "POST", headers: auth, body: JSON.stringify({ amount: opening }),
    });
    if (r.status !== 201) throw new Error(`seed deposit failed: ${r.status} ${r.body}`);
  }
  return account;
}

async function reconcile(auth, accountId) {
  let page = 0, all = [];
  while (true) {
    const r = await timedFetch(`/api/accounts/${accountId}/transactions?page=${page}&size=100`, { headers: auth });
    const body = JSON.parse(r.body);
    all = all.concat(body.content);
    if (page >= body.totalPages - 1) break;
    page++;
  }
  let balance = 0;
  for (const t of all) {
    const amt = parseFloat(t.amount);
    balance += (t.type === "DEPOSIT" || t.type === "TRANSFER_IN") ? amt : -amt;
  }
  const stored = JSON.parse((await timedFetch(`/api/accounts/${accountId}`, { headers: auth })).body).balance;
  return { computed: balance.toFixed(2), stored, matches: balance.toFixed(2) === parseFloat(stored).toFixed(2) };
}

async function main() {
  console.log(`Instances: ${INSTANCES.join(", ")}`);
  console.log(`Will kill container "${KILL_CONTAINER}" ~${KILL_AFTER_MS}ms into the run`);

  const auth = await register();
  const source = await openAccount(auth, "1000.00");
  const destination = await openAccount(auth, "0.00");

  const killTimer = setTimeout(() => {
    console.log(`\n>>> killing ${KILL_CONTAINER}`);
    execSync(`docker kill ${KILL_CONTAINER}`, { stdio: "inherit" });
  }, KILL_AFTER_MS);

  const total = 60;
  const results = await Promise.all(Array.from({ length: total }, () =>
    timedFetch("/api/transfers", {
      method: "POST", headers: auth,
      body: JSON.stringify({ fromAccountId: source.id, toAccountId: destination.id, amount: "5.00" }),
    })
  ));
  clearTimeout(killTimer);

  const succeeded = results.filter((r) => r.status === 201).length;
  const connectionFailures = results.filter((r) => r.failed).length;
  const otherFailures = results.filter((r) => !r.failed && r.status !== 201).length;

  console.log("\n=== Chaos test results ===");
  console.log(`succeeded: ${succeeded}/${total}`);
  console.log(`connection failures (instance down): ${connectionFailures}`);
  console.log(`other non-201 responses: ${otherFailures}`);

  console.log("\nWaiting 2s for in-flight work to settle before reconciling...");
  await new Promise((resolve) => setTimeout(resolve, 2000));

  const sourceRecon = await reconcile(auth, source.id);
  const destRecon = await reconcile(auth, destination.id);
  console.log(`source reconciles: ${sourceRecon.matches} (computed=${sourceRecon.computed}, stored=${sourceRecon.stored})`);
  console.log(`destination reconciles: ${destRecon.matches} (computed=${destRecon.computed}, stored=${destRecon.stored})`);

  const expectedDelta = (succeeded * 5).toFixed(2);
  const actualDelta = parseFloat(destRecon.stored).toFixed(2);

  console.log(`\nsucceeded transfers moved $${expectedDelta} (5.00 x ${succeeded}); destination balance is $${actualDelta}`);

  if (!sourceRecon.matches || !destRecon.matches) {
    throw new Error("CHAOS TEST FAILED: ledger did not reconcile after killing an instance");
  }
  if (expectedDelta !== actualDelta) {
    throw new Error(
      "CHAOS TEST FAILED: destination balance does not match the count of successful transfers - " +
      "money moved without a matching success response, or vice versa"
    );
  }
  if (connectionFailures === 0) {
    console.warn(
      "\nWARNING: no connection failures observed - the kill may not have landed mid-request. " +
      "Try lowering LEDGER_KILL_AFTER_MS or increasing load."
    );
  }

  console.log("\nChaos test passed: the ledger reconciled correctly despite a mid-run instance kill.");
  console.log(`Remember to restart the killed instance: docker compose --profile multi up -d ${KILL_CONTAINER}`);
}

main().catch((e) => { console.error(e); process.exit(1); });
```

- [ ] **Step 2: Run it against the multi-instance stack**

Run: `docker compose --profile multi up -d --build postgres app1 app2 app3`

Wait ~60s for all three to report healthy, then:

Run: `node scripts/chaos-test.mjs`
Expected: prints at least one connection failure, ends with `Chaos test passed:...`, exit
code `0`. If `connectionFailures` is `0`, the warning is informational, not a failure — see
the script's own suggestion (lower `LEDGER_KILL_AFTER_MS`) and re-run once to confirm the
kill can land mid-request; either outcome that reaches "Chaos test passed" is acceptable.

Run: `docker compose --profile multi up -d ledger-app2` (restart the killed container)
Run: `docker compose --profile multi down`

- [ ] **Step 3: Commit**

```bash
git add scripts/chaos-test.mjs
git commit -m "feat: add chaos test for instance failure during concurrent transfers"
```

---

### Task 10: README — Chaos testing section

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: nothing (documentation only).

- [ ] **Step 1: Insert the section and update the Table of Contents**

In `README.md`, change:

```markdown
- [Concurrency safety](#concurrency-safety)
- [Multi-instance concurrency](#multi-instance-concurrency)
- [Observability](#observability)
- [Tests](#tests)
```

to:

```markdown
- [Concurrency safety](#concurrency-safety)
- [Multi-instance concurrency](#multi-instance-concurrency)
- [Chaos testing](#chaos-testing)
- [Observability](#observability)
- [Tests](#tests)
```

Then, immediately after the `## Multi-instance concurrency` section added in Task 8 and
before `## Observability`, insert:

```markdown
## Chaos testing

```bash
docker compose --profile multi up -d --build postgres app1 app2 app3
node scripts/chaos-test.mjs
```

[`scripts/chaos-test.mjs`](scripts/chaos-test.mjs) fires concurrent transfers across all
three instances and, partway through, kills one of them (`docker kill`) — then reconciles
balances afterward.

**What this proves:** the ledger never ends up in a corrupted state — the destination
balance always matches exactly `$5.00 × (number of successful transfers)`, and every
account's transaction log reconciles with its stored balance, whether or not the kill
lands mid-request.

**What this does not prove:** that no individual request ever fails. A request being
processed on the killed instance at the exact moment it dies has no way to complete —
there's no distributed transaction between the load balancer and the app. That's expected
and is not a bug: the client sees a clear connection failure rather than an ambiguous
outcome, and can safely retry using the [idempotency key](#idempotency) it should already
be sending for a money-moving request.

## Observability
```

- [ ] **Step 2: Verify the README renders sensibly**

Run: `grep -c "^## " README.md`
Expected: one more heading than after Task 8.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: add Chaos testing section"
```

---

### Task 11: AWS scaling script

**Files:**
- Create: `infra/scale.sh`
- Modify: `infra/README.md`

**Interfaces:**
- Consumes: `terraform output ecs_cluster_name` / `ecs_service_name` (both already defined
  in `infra/outputs.tf`); the `aws` CLI, already required by the rest of `infra/`.
- Produces: `infra/scale.sh <count>` — a runnable script; no other task depends on it.

- [ ] **Step 1: Write the scaling script**

Create `infra/scale.sh`:

```bash
#!/usr/bin/env bash
# Scales the live ECS service's task count. NOT done via Terraform: the service's
# `lifecycle { ignore_changes = [task_definition, desired_count] }` (see ecs.tf) deliberately
# stops `terraform apply` from fighting the deploy pipeline over the running count, the same
# reason it already does so for the image version - so scaling happens the same way an image
# deploy does, outside Terraform.
#
# Usage: infra/scale.sh <count>
set -euo pipefail

COUNT="${1:?Usage: infra/scale.sh <count>}"
CLUSTER=$(terraform -chdir=infra output -raw ecs_cluster_name)
SERVICE=$(terraform -chdir=infra output -raw ecs_service_name)

aws ecs update-service \
  --cluster "$CLUSTER" \
  --service "$SERVICE" \
  --desired-count "$COUNT" \
  --query 'service.{cluster:clusterArn,service:serviceName,desiredCount:desiredCount}' \
  --output table

echo "Scaled to $COUNT. Waiting for the service to stabilize..."
aws ecs wait services-stable --cluster "$CLUSTER" --services "$SERVICE"
echo "Stable at $COUNT tasks."
```

- [ ] **Step 2: Make it executable**

Run: `chmod +x infra/scale.sh`

- [ ] **Step 3: Document it in infra/README.md**

In `infra/README.md`, find the existing "Who owns what" section, which currently ends
with:

```markdown
## Who owns what

Terraform owns the infrastructure. **The pipeline owns which image version is running.** The ECS
service carries `lifecycle { ignore_changes = [task_definition, desired_count] }`, so Terraform will
not revert a deployment made by CI. Terraform's task definition is only ever the bootstrap revision.

## Tearing it down
```

Insert a new section between them:

```markdown
## Who owns what

Terraform owns the infrastructure. **The pipeline owns which image version is running.** The ECS
service carries `lifecycle { ignore_changes = [task_definition, desired_count] }`, so Terraform will
not revert a deployment made by CI. Terraform's task definition is only ever the bootstrap revision.

## Scaling the service

```bash
infra/scale.sh 3
```

Same reasoning as "Who owns what" above: `desired_count` is in the service's
`ignore_changes` list, so editing the number in `ecs.tf` and running `terraform apply`
would have **no effect** — Terraform is instructed to ignore drift on that field. Scaling
happens the same way an image deploy does: outside Terraform, via the AWS CLI.

`infra/scale.sh <count>` reads the cluster and service names from Terraform outputs, calls
`aws ecs update-service --desired-count`, and waits for the service to stabilize before
returning. Scale back down with `infra/scale.sh 1`.

## Tearing it down
```

- [ ] **Step 4: Verify the shell script is syntactically valid**

Run: `bash -n infra/scale.sh`
Expected: no output (a `set -euo pipefail` script with a syntax error would print a parse
error here).

- [ ] **Step 5: Commit**

```bash
git add infra/scale.sh infra/README.md
git commit -m "feat: add infra/scale.sh for scaling the ECS service outside Terraform"
```

---

### Task 12: Main README — Deployment, Highlights, and final wiring

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: everything from Tasks 1-11 (this is the final documentation pass tying them
  together).

- [ ] **Step 1: Add a Highlights bullet**

In `README.md`, change:

```markdown
- **Infrastructure as code, deployed for real.** Terraform provisions the full AWS stack (ECS
  Fargate, RDS, ALB, IAM, OIDC federation); GitHub Actions tests, builds, and deploys on every push
  to `main`, authenticating with zero long-lived AWS credentials. See [Deployment](#deployment).
```

to:

```markdown
- **Infrastructure as code, deployed for real.** Terraform provisions the full AWS stack (ECS
  Fargate, RDS, ALB, IAM, OIDC federation); GitHub Actions tests, builds, and deploys on every push
  to `main`, authenticating with zero long-lived AWS credentials. See [Deployment](#deployment).
- **Proven across processes, not just threads.** The concurrency guarantee above holds when
  concurrent transfers land on different application instances, not only different threads in one
  JVM — verified by a script, and by a chaos test that kills an instance mid-transfer. See
  [Multi-instance concurrency](#multi-instance-concurrency) and [Chaos testing](#chaos-testing).
```

- [ ] **Step 2: Update the Deployment section**

In `README.md`, change:

```markdown
- **Single-AZ, one task, HTTP only.** Deliberate: this is sized to be stood up for a demo and torn
  down in one command, not to be highly available. Multi-AZ RDS, an ACM certificate with an HTTPS
  listener, and auto-scaling are each a small, well-defined addition.

Roughly $55/month while running continuously — see [`infra/README.md`](infra/README.md) for the
full bootstrap runbook, including the exact `terraform apply` / `terraform destroy` sequence.
```

to:

```markdown
- **Single-AZ, HTTP only.** Deliberate: this is sized to be stood up for a demo and torn down in
  one command, not to be highly available. Multi-AZ RDS and an ACM certificate with an HTTPS
  listener are each a small, well-defined addition. Task count is not one of them anymore — see
  below.

The service normally runs 1 task (~$55/month while running continuously); `infra/scale.sh 3`
scales it to match the [multi-instance concurrency](#multi-instance-concurrency) and
[chaos testing](#chaos-testing) sections' local setup, for real, on the live ALB. Scale back down
with `infra/scale.sh 1`. See [`infra/README.md`](infra/README.md) for the full bootstrap runbook
and the "Scaling the service" section for why this is a script and not a Terraform variable.
```

- [ ] **Step 3: Verify the full README structure**

Run: `grep "^## " README.md`
Expected, in order: `## Highlights`, `## Live Demo`, `## Table of Contents`, `## Stack`,
`## Running it`, `## Trying it`, `## Architecture`, `## Concurrency safety`,
`## Multi-instance concurrency`, `## Chaos testing`, `## Observability`, `## Tests`,
`## Performance`, `## API`, `## Idempotency`, `## Design decisions and limits`,
`## Configuration`, `## Deployment`, `## License`.

Run: `grep "^\s*- \[" README.md | grep -oP '(?<=\[).*?(?=\])'`
Expected: this list of link texts matches the heading order above exactly (Table of
Contents in sync with actual headings).

- [ ] **Step 4: Full local smoke test of everything built in this plan**

Run:
```bash
docker compose --profile multi up -d --build postgres app1 app2 app3 prometheus grafana
```

Wait ~60s, then run all three verification scripts in sequence:
```bash
node scripts/multiinstance-test.mjs
node scripts/chaos-test.mjs
docker compose --profile multi up -d ledger-app2  # restart what chaos-test.mjs killed
```

Expected: both scripts print their success message and exit `0`. Open
`http://localhost:3000`, confirm the LedgerAPI dashboard shows non-zero retry-rate activity
from the runs above.

Run: `./mvnw -q test` (all instances still running is fine — Testcontainers uses its own
throwaway Postgres, unrelated to the compose stack)
Expected: `Tests run: 149, Failures: 0, Errors: 0`

Run: `docker compose --profile multi down`

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: tie multi-instance, chaos, and observability sections into Highlights and Deployment"
```

## Self-Review

**Spec coverage:**
- Multi-instance concurrency proof (spec Component 1) → Tasks 6-8. ✓
- Observability (spec Component 2) → Tasks 1-5. ✓
- Chaos testing (spec Component 3) → Tasks 9-10. ✓
- Deployment (spec Component 4) → Task 11. ✓
- Documentation (spec Component 5) → Tasks 5, 8, 10, 12. ✓
- Non-goal "no new local load balancer" → honored: scripts round-robin directly, no nginx/
  Traefik introduced. ✓
- Non-goal "no DB replication" → honored: no database changes anywhere in this plan. ✓

**Placeholder scan:** no TBD/TODO; every step shows complete, runnable code or an exact
command with expected output.

**Type/name consistency:** `ConflictRetry(MeterRegistry)` (Task 2) is the constructor every
later reference to `ConflictRetry` assumes (Task 3's `IdempotencyServiceTest` mock doesn't
call the real constructor, so it's unaffected). Metric names
(`ledger.transfer.retry.count`, `ledger.transfer.duration`, `ledger.idempotency.replay.count`)
are identical across Tasks 2, 3, 4 (dashboard queries), and 5 (README table). Container
names (`ledger-app1/2/3`, Task 6) match what Tasks 7, 9, and 12 reference. Script env var
names (`LEDGER_API_INSTANCES`, `LEDGER_KILL_CONTAINER`, `LEDGER_KILL_AFTER_MS`) are
consistent between Tasks 7 and 9 and their README documentation in Tasks 8 and 10.
