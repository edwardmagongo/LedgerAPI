# Multi-instance concurrency, observability, and chaos testing — design

Status: approved
Date: 2026-08-08

## Overview

LedgerAPI's `TransferConcurrencyTest` proves that concurrent transfers against the same
account cannot corrupt a balance — but it proves this across **threads within one JVM**.
That is not the same claim as correctness across **separate processes**, which is what a
real horizontally-scaled deployment actually does. This extension closes that gap, and
adds the observability layer needed to see the concurrency story (retries, conflicts) as
it happens, plus a chaos test proving an instance dying mid-transfer doesn't corrupt the
ledger.

This is an extension of the existing LedgerAPI repository, not a new project. It reuses
the existing docker-compose local setup, the existing Terraform/ECS deployment, and the
existing `scripts/loadtest.mjs` pattern for externally-driven, script-based verification.

## Goals

- Prove optimistic-locking correctness holds when concurrent transfers on the same account
  are handled by **different application instances**, not just different threads.
- Make the concurrency story (retries, conflicts, idempotency replays) observable in real
  time via metrics and dashboards, not just inferable from test assertions.
- Prove that killing an application instance mid-transfer fails visibly rather than
  corrupting the ledger.
- Deploy the same guarantee for real: scale the existing AWS ECS service to multiple tasks.

## Non-goals

- Database-level distribution (replication, failover, sharding). Out of scope for this
  extension — noted as a possible future addition, not attempted here.
- A new load-balancer component for local development. The test harness round-robins
  across known ports directly; no nginx/Traefik/etc. is introduced.
- Re-deriving or re-validating anything about the QIANets research. Unrelated to this work.
- High availability guarantees beyond "the ledger stays correct." Single-AZ, no
  auto-scaling, no zero-downtime deploys — consistent with LedgerAPI's existing "sized to
  be stood up for a demo" scope.

## Architecture

### Local topology

`docker-compose` runs three named application containers (`app1`, `app2`, `app3` — ports
`8081`, `8082`, `8083`) against the existing single Postgres container. No new
load-balancing component: verification scripts round-robin across the three known ports
directly, since the thing being proven is cross-process correctness, not load-balancer
behavior.

### AWS topology

No new infrastructure. The existing Terraform-managed ECS service's `desired_count`
changes from `1` to `3`; the existing Application Load Balancer already distributes
requests across all running tasks in a service. This is a one-line Terraform change.

## Components

### 1. Multi-instance concurrency verification

**What:** A new script, `scripts/multiinstance-test.mjs`, structured like the existing
`scripts/loadtest.mjs`. It runs the same contended scenarios `TransferConcurrencyTest`
runs in-JVM (20-way contention on a single account, fan-in, bidirectional transfers,
overdraft-limited contention), but dispatches requests round-robin across `app1`/`app2`/
`app3` over HTTP, then reconciles balances from the transaction log afterward — identical
verification method to the existing JUnit test, different execution substrate.

**Why a script, not a JUnit test:** `@SpringBootTest` starts one application context per
test JVM. Proving cross-*process* correctness requires actually separate running
processes, which only exists once `docker-compose` has started them — the same reason
`loadtest.mjs` is a script and not a test.

**Depends on:** the `docker-compose` three-instance topology; the existing auth/account/
transfer API surface (no API changes needed).

### 2. Observability

**What:** `micrometer-registry-prometheus` added to `pom.xml`; `/actuator/prometheus`
exposed. Beyond Spring's default metrics, three custom metrics tied directly to the
concurrency story already documented in the README:

- `ledger.transfer.retry.count` — counter, incremented on each optimistic-lock retry
  attempt, tagged by outcome (`succeeded`, `exhausted`).
- `ledger.idempotency.replay.count` — counter, incremented when a request is served from
  a stored idempotent response rather than executed.
- `ledger.transfer.duration` — histogram of transfer request latency.

`docker-compose` gains `prometheus` and `grafana` services. `prometheus.yml` (checked into
the repo) scrapes all three app instances' `/actuator/prometheus` endpoints. A Grafana
dashboard JSON (checked into the repo, provisioned automatically on container start) shows
retry rate, idempotency replay rate, and latency percentiles — so the retry counter visibly
spikes while `multiinstance-test.mjs` is hammering a single account.

**Depends on:** nothing new architecturally — additive to the existing Spring Boot
Actuator setup already in the project.

### 3. Chaos testing

**What:** `scripts/chaos-test.mjs`. Fires concurrent transfer load across all three
instances (reusing the multi-instance harness), and partway through, runs `docker kill` on
one app container. After the run, it asserts:

- Requests in flight to the killed instance fail with a clear error (connection
  reset/timeout) rather than silently disappearing.
- The two surviving instances continue serving correctly.
- Balance reconciliation (recomputing from the transaction log) shows **zero corruption** —
  every completed transfer is fully applied exactly once; nothing is partially applied.

**Explicit, honestly-stated limitation:** a request that is being processed on the killed
instance *at the moment of the kill* can fail visibly to the caller — there is no
distributed transaction between the load balancer and the app, so an in-flight request has
no guarantee of completing. The guarantee under test is narrower and more important: the
*ledger* never ends up in a corrupted state, even though an individual *request* can fail.
This distinction gets stated directly in the README, not glossed over.

**Depends on:** the multi-instance topology and harness from Component 1.

### 4. Deployment

**What:** `infra/*.tf` — change `desired_count` on the ECS service resource from `1` to
`3`. No other Terraform changes. `terraform apply` scales the live deployment; the
existing ALB health checks and target group already handle routing to healthy tasks.

### 5. Documentation

New README sections, inserted after the existing "Concurrency safety" section (extending
its narrative rather than duplicating it):

- **Multi-instance concurrency** — the cross-process proof, how to run it, what it shows.
- **Observability** — how to run the Prometheus/Grafana stack locally, what the three
  custom metrics mean, a screenshot of the dashboard mid-load-test.
- **Chaos testing** — how to run it, what's proven, the explicit in-flight-request caveat
  above.

Existing "Deployment" section updated to note `desired_count = 3` and what that means for
cost (roughly 3x the existing ECS compute line).

## Testing strategy

- `scripts/multiinstance-test.mjs` and `scripts/chaos-test.mjs` are the verification
  artifacts for this work, run manually against the `docker-compose` stack — matching how
  `scripts/loadtest.mjs` already works. Not part of `./mvnw test` (they require the
  multi-container stack running, same reason the existing load test isn't part of the
  Maven test suite).
- No changes to the existing 145 JUnit tests — this work is additive.
- Observability metrics are verified by inspecting `/actuator/prometheus` output directly
  during development, and visually via the Grafana dashboard during a load-test run.

## Milestones

1. **Observability** — Micrometer/Prometheus dependency, custom metrics, docker-compose
   Prometheus+Grafana services, dashboard JSON. Smallest, most mechanical, immediately
   useful on its own (works even before the multi-instance topology exists).
2. **Multi-instance topology + concurrency proof** — three-instance docker-compose setup,
   `scripts/multiinstance-test.mjs`.
3. **Chaos testing** — `scripts/chaos-test.mjs`, built on Milestone 2's harness.
4. **AWS deployment + documentation** — Terraform `desired_count` change, README updates
   tying all three pieces together.

## Open questions / explicitly deferred

- Database replication/failover: deferred, noted as a possible future addition in the
  README's existing "Design decisions and limits" section rather than attempted here.
- Whether to add distributed tracing (OpenTelemetry) across instances: not in scope for
  this pass — Prometheus metrics plus existing structured error responses are the bar for
  this extension. Could be a future addition once this lands.
