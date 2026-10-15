# OrderFlow

OrderFlow is a distributed order processing platform built with Java 21, Spring Boot, Spring Cloud, PostgreSQL, Kafka, Redis, and Docker.

The project is designed to demonstrate production-oriented microservice patterns including service discovery, API gateway routing, event-driven communication, transactional outbox, consumer idempotency, optimistic locking, retry handling, concurrent-safe background workers, and Saga-style compensation.

---

## Architecture

```text
                              ┌─────────────────────┐
                              │    Eureka Server    │
                              │        :8761        │
                              └──────────┬──────────┘
                                         │
                               Service Registration
                                         │
                    ┌────────────────────┼────────────────────┐
                    │                    │                    │
                    ▼                    ▼                    ▼
          ┌─────────────────┐   ┌─────────────────┐  ┌─────────────────┐
          │ Gateway Service │   │  Order Service  │  │Inventory Service│
          │      :9000      │   │      :8081      │  │      :8082      │
          └────────┬────────┘   └────────┬────────┘  └────────┬────────┘
                   │                     │                    │
                   │                     │                    │
             HTTP / REST                 │                    │
                   │                     │                    │
                   └──────────────► Order API                 │
                                         │                    │
                                         ▼                    ▼
                                  ┌──────────────┐     ┌──────────────┐
                                  │ PostgreSQL   │     │ PostgreSQL   │
                                  │   orders     │     │  inventory   │
                                  │outbox_events │     │reservations  │
                                  └──────┬───────┘     └──────┬───────┘
                                         │                    │
                                         │ Transactional      │
                                         │ Outbox             │
                                         ▼                    │
                                  ┌──────────────┐             │
                                  │    Kafka     │◄────────────┘
                                  │    :9092     │
                                  └──────┬───────┘
                                         │
                     ┌───────────────────┴───────────────────┐
                     │                                       │
                     ▼                                       ▼
              order-events                           inventory-events
                     │                                       │
                     ▼                                       ▼
             Inventory Service                       Order Service
```

---

## Current Services

| Service | Port | Responsibility |
|---|---:|---|
| Discovery Service | 8761 | Eureka service discovery |
| Gateway Service | 9000 | API routing and service lookup |
| Order Service | 8081 | Order creation, persistence, Outbox publishing |
| Inventory Service | 8082 | Inventory reservation and compensation |

---

## Technology Stack

### Backend

- Java 21
- Spring Boot 4.1.1
- Spring Cloud
- Spring Data JPA
- Resilience4j (reactive Gateway circuit breakers)
- Hibernate
- Bean Validation
- Maven

### Infrastructure

- PostgreSQL 16
- Apache Kafka 4.2.1
- Redis 7
- Docker / Docker Compose
- Flyway
- Eureka
- Spring Cloud Gateway

### Reliability Patterns

- Transactional Outbox
- Saga Compensation
- Consumer Idempotency
- Optimistic Locking
- Retry with Exponential Backoff
- Dead Letter Topic
- Concurrent-safe Outbox Workers
- Stale Lock Recovery

---

## Request Flow

A client creates an order through the API Gateway:

```text
Client
   │
   ▼
Gateway :9000
   │
   ▼
Eureka
   │
   ▼
Order Service :8081
   │
   ├── INSERT orders
   │
   └── INSERT outbox_events
          │
          ▼
      same database
      transaction
```

The order and its corresponding Outbox event are committed atomically.

Kafka is not called directly inside the order creation transaction.

---

## Transactional Outbox

Order creation stores both the business record and an Outbox event inside the same PostgreSQL transaction.

```text
BEGIN
   │
   ├── INSERT INTO orders
   │
   └── INSERT INTO outbox_events
   │
COMMIT
```

Example Outbox event:

```text
aggregate_type = ORDER
aggregate_id   = <orderId>
event_type     = ORDER_CREATED
status         = PENDING
retry_count    = 0
```

An asynchronous Outbox Publisher later sends the event to Kafka.

This prevents the classic dual-write problem where the database transaction succeeds but Kafka publishing fails.

---

## Outbox Lifecycle

```text
             ┌─────────────┐
             │   PENDING   │
             └──────┬──────┘
                    │
                    │ claim
                    ▼
             ┌─────────────┐
             │ PROCESSING  │
             └──────┬──────┘
                    │
          ┌─────────┴─────────┐
          │                   │
     Kafka success       Kafka failure
          │                   │
          ▼                   ▼
   ┌─────────────┐      retry_count + 1
   │  PUBLISHED  │             │
   └─────────────┘             │
                         ┌─────┴─────┐
                         │           │
                    retry left   retry exhausted
                         │           │
                         ▼           ▼
                     PENDING       FAILED
```

---

## Outbox Retry Metadata

The Outbox table tracks publishing attempts using:

```text
retry_count
next_attempt_at
last_error
locked_at
locked_by
```

Example retry sequence:

```text
Attempt 1 failed → wait 5 seconds
Attempt 2 failed → wait 10 seconds
Attempt 3 failed → wait 20 seconds
Attempt 4 failed → wait 40 seconds
Attempt 5 failed → FAILED
```

If Kafka becomes available again before the maximum retry count is reached, the event is automatically retried and can transition to:

```text
PENDING
   ↓
PROCESSING
   ↓
PUBLISHED
```

The previous `retry_count` is preserved for observability.

---

## Concurrent-Safe Outbox Publishing

Multiple Order Service instances may run simultaneously.

To prevent two instances from claiming the same Outbox record, PostgreSQL row-level locking is used:

```sql
SELECT *
FROM outbox_events
WHERE (
    status = 'PENDING'
    OR (
        status = 'PROCESSING'
        AND locked_at < :staleBefore
    )
)
AND next_attempt_at <= :now
ORDER BY created_at
LIMIT :batchSize
FOR UPDATE SKIP LOCKED;
```

Each Order Service instance has its own worker ID.

Example:

```text
Order Service Instance A
workerId=a4b82499-...

Order Service Instance B
workerId=839f31d5-...
```

If Instance A locks an Outbox row:

```text
Instance A
   │
   └── claim event A

Instance B
   │
   ├── event A locked
   │
   └── SKIP → claim event B
```

This allows multiple Outbox workers to process events concurrently without blocking each other.

---

## Stale Lock Recovery

An Order Service instance may crash after marking an event as `PROCESSING`.

Example:

```text
PENDING
   ↓
PROCESSING
   ↓
Order Service crashes
```

Without recovery, the event would remain stuck.

OrderFlow considers a `PROCESSING` event stale after a configured timeout.

```text
PROCESSING
locked_at = older than timeout
        │
        ▼
eligible for another worker
        │
        ▼
PROCESSING
```

This allows another Order Service instance to recover abandoned work.

---

## Kafka Event Flow

Order Service publishes:

```text
order-events
```

Inventory Service consumes the event and attempts to reserve inventory.

```text
Order Service
     │
     │ ORDER_CREATED
     ▼
 order-events
     │
     ▼
Inventory Service
```

Inventory Service publishes the reservation result:

```text
inventory-events
```

Order Service consumes the inventory result.

---

## Inventory Reservation

Inventory Service performs reservation inside a database transaction.

Inventory records use optimistic locking:

```java
@Version
private Long version;
```

This prevents lost updates when multiple requests attempt to modify the same inventory record concurrently.

---

## Saga Compensation

If inventory reservation fails after one or more items have already been reserved, Inventory Service compensates the completed operations.

Example:

```text
Order contains:

SKU-A
SKU-B
SKU-C

Reserve SKU-A ✅
Reserve SKU-B ✅
Reserve SKU-C ❌

        ↓

Compensation

Release SKU-A
Release SKU-B
```

Reservation release is idempotent.

Calling release multiple times does not incorrectly increase inventory.

Example verified behavior:

```text
Before reserve:
quantity = 11
reserved = 4

After reserve:
quantity = 10
reserved = 5

After first release:
quantity = 11
reserved = 4

After second release:
quantity = 11
reserved = 4
```

---

## Kafka Retry and Dead Letter Topic

Inventory Service uses Kafka error handling with retry and Dead Letter Topic support.

Configuration:

```text
Retry delay: 2 seconds
Retries: 2
Total attempts: 3
```

Failed messages can be routed to:

```text
order-events-dlt
```

This prevents permanently failing events from blocking normal Kafka processing.

---

## Consumer Idempotency

Kafka delivery is at-least-once.

An event may therefore be delivered more than once.

Inventory Service uses a `processed_events` table to detect previously processed events.

```text
Kafka event
    │
    ▼
Check processed_events
    │
    ├── already processed → ignore
    │
    └── new event
            │
            ▼
       execute business logic
            │
            ▼
       save event ID
```

This prevents duplicate Kafka delivery from executing the same inventory operation multiple times.

Consumer idempotency is especially important with Transactional Outbox because a producer can crash after Kafka acknowledges an event but before the Outbox row is marked `PUBLISHED`.

---

## Delivery Semantics

OrderFlow currently implements:

```text
Transactional Outbox
        +
Kafka at-least-once delivery
        +
Consumer Idempotency
```

This provides reliable event delivery while preventing duplicate business side effects.

It does not assume distributed exactly-once transactions across PostgreSQL and Kafka.

---

## Database Migrations

Flyway manages database schema changes.

Order Service includes migrations for:

```text
orders
outbox_events
retry metadata
Outbox locking metadata
```

Inventory Service includes migrations for:

```text
inventory
inventory_reservations
processed_events
```

---

## Run with Docker

Prerequisites: Docker Desktop with Linux containers and Docker Compose v2.20+.
Java and Maven are supplied by the four Java 21 multi-stage image builds.

```powershell
Copy-Item .env.example .env
docker compose up -d --build --wait --wait-timeout 300
docker compose ps
```

This starts PostgreSQL, Redis, Kafka and the four Java services (including Eureka)
on one Compose network. The Gateway is at `http://localhost:9000`; Eureka is at
`http://localhost:8761`. All published ports bind to the local machine.
See [.env.example](.env.example) for port and credential settings.

```powershell
# Automated, isolated end-to-end and failure-recovery checks (Python 3.10+).
python scripts/docker-smoke.py
# Reuse images already built by Compose:
python scripts/docker-smoke.py --skip-build

# Stop the development stack while retaining all data:
docker compose down
```

The Smoke Test uses a unique Compose project, fresh volumes, random host ports
and separate database credentials. It verifies Saga success/compensation,
Outbox retries, duplicate Kafka delivery, Redis fallback, Day12 circuit breaking,
JVM crash restart and persistence across container recreation. Reports and logs
are saved under `integration-tests/target/docker-smoke/<project>/`.

For IntelliJ development, start only infrastructure and run the four Java
applications with their existing default profile:

```powershell
docker compose up -d --wait postgres redis kafka
```

Keep the default ports/credentials when using the unchanged IntelliJ settings.
Stop the containerized Java services first if switching from the full stack.
Kafka advertises `kafka:29092` inside Docker and `localhost:9092` to local JVMs.
See the [Docker runbook](docs/day13-docker.md) for profiles, health checks,
data volumes, observability and recovery instructions.

---

## Create Order

Requests are routed through the Gateway:

```http
POST http://localhost:9000/api/orders
```

Example:

```json
{
  "customerId": "11111111-1111-1111-1111-111111111114",
  "items": [
    {
      "sku": "IPHONE17PRO-256-BLK",
      "quantity": 1
    }
  ],
  "totalAmount": 99.99,
  "currency": "USD"
}
```

---

## Useful Outbox Queries

Check recent Outbox events:

```sql
SELECT
    id,
    aggregate_id,
    event_type,
    status,
    retry_count,
    next_attempt_at,
    last_error,
    locked_at,
    locked_by,
    created_at,
    published_at
FROM outbox_events
ORDER BY created_at DESC;
```

Check events that have not yet been published:

```sql
SELECT
    id,
    aggregate_id,
    status,
    retry_count,
    next_attempt_at,
    last_error
FROM outbox_events
WHERE status <> 'PUBLISHED'
ORDER BY created_at DESC;
```

Check Outbox status distribution:

```sql
SELECT
    status,
    COUNT(*)
FROM outbox_events
GROUP BY status;
```

---

## Reliability Tests Completed

The following scenarios have been manually verified:

```text
Order persistence
        ✅

Gateway → Eureka → Order Service routing
        ✅

Kafka order event publishing
        ✅

Inventory reservation
        ✅

Optimistic locking
        ✅

Kafka retry
        ✅

Dead Letter Topic
        ✅

Saga compensation
        ✅

Idempotent reservation release
        ✅

Transactional Outbox
        ✅

Consumer idempotency
        ✅

Outbox retry metadata
        ✅

Kafka unavailable → retry
        ✅

Kafka recovery → automatic publish
        ✅

Concurrent Outbox workers
        ✅

PostgreSQL FOR UPDATE SKIP LOCKED
        ✅

Stale PROCESSING event recovery
        ✅
```

---

## Current Development Status

Day 9 Saga Workflow development (steps 1–9): see [workflow, recovery APIs, and rollout notes](docs/day9-saga-workflow.md).
Step 10 adds [isolated integration tests and run instructions](integration-tests/README.md).
See the [latest integration test summary](docs/day9-integration-test-summary.md) for executed scenarios and results.

Day 10 adds health probes, Prometheus metrics, JSON logs, durable HTTP/Kafka tracing,
Grafana dashboards and recovery alerts. See the [observability runbook](docs/day10-observability.md),
[executed Day10 summary](docs/day10-observability-summary.md), and
[verification runner](scripts/run-day10-tests.ps1).

Day 11 adds Redis order and inventory HTTP read caches (45s/5s TTL), Saga and
stock invalidation after commit, and database fallback during Redis failure.
All 65 cache/end-to-end/Day9/Day10 regression checks passed. See the
[Day11 runbook and consistency limits](docs/day11-redis-cache.md) and
[final test summary](docs/day11-redis-cache-summary.md).
Run `powershell -File scripts/run-day11-tests.ps1` for isolated cache checks;
the runbook lists the subsequent regression and summary commands.

Day 12 adds separate Order/Inventory HTTP circuit breakers, bounded connection/
response timeouts, GET-only retries, standard 503/504 fallback and per-route
concurrency protection. Kafka/Saga recovery remains independent; POST is never
automatically retried, including connection resets. See the
[Day12 runbook](docs/day12-resilience.md) and
[executed test summary](docs/day12-resilience-summary.md): **79/79 checks passed**,
including all Day9-Day11 regressions and live Grafana dashboard validation.
Run `powershell -File scripts/run-day12-tests.ps1 -RunRegressions` to reproduce.
Rate limiting remains optional and deferred.

Day 13 adds Java 21 container images, a complete seven-service Compose stack,
Docker profiles and an isolated, automated container Smoke Test. See the
[Docker runbook](docs/day13-docker.md) and [verification results](docs/day13-docker-summary.md).

Completed:

```text
Day 1
Project setup
Eureka
Docker infrastructure

Day 2
Order Service
PostgreSQL
Flyway
JPA
Validation

Day 3
API Gateway
Service discovery
Kafka setup

Day 4–5
Inventory Service
Inventory persistence
Kafka integration

Day 6
Kafka retry
Dead Letter Topic
Saga compensation
Optimistic locking

Day 7
Transactional Outbox
Consumer idempotency
processed_events

Day 8
Concurrent-safe Outbox publishing
FOR UPDATE SKIP LOCKED
Retry metadata
Exponential backoff
Stale lock recovery
Multi-instance Outbox testing

Day 9
Durable order/inventory Saga
Multi-SKU transactions and compensation
Isolated end-to-end integration tests

Day 10
Health probes and Prometheus business metrics
Structured logs and durable HTTP/Kafka tracing
Grafana dashboards and recovery alerts
Observability fault-injection tests

Day 11
Redis order and inventory read caches (45s/5s TTL)
After-commit invalidation and Redis failure fallback
HTTP/Kafka/Redis tests and Day9/Day10 regression

Day 12
Reactive Gateway circuit breakers and bounded HTTP timeouts
GET-only retries, standard fallback and concurrency protection
Resilience metrics/dashboard and fault-injection Saga consistency tests
Day9-Day11 regression

Day 13
Java 21 multi-stage images and seven-service Docker Compose
Docker profiles, service DNS, health checks and persistent data volumes
Container workflow, fault recovery and persistence Smoke Test
```

---

## Continuous Integration (Day 14)

[CI runs](https://github.com/Maknight233/OrderFlow/actions/workflows/ci.yml) execute
on every branch Push and Pull Request using Java 21 and a Maven dependency cache.
`build-test` packages all four services and runs the three standalone
`GatewayConcurrencyFilterTest` tests. Once it passes, `docker-smoke` builds the
four images and runs the seven-service, 15-check Docker test suite.

```powershell
mvn -B -ntp -DskipTests package
mvn -B -ntp -pl gateway-service '-Dtest=GatewayConcurrencyFilterTest' test
python scripts/docker-smoke.py
```

Download the `maven-reports-*` and `docker-smoke-reports-*` artifacts from the
Actions run page. Reports upload on success and failure and are retained for
14 days, including Surefire XML, Docker `report.json`, Compose output, HTTP
request timings and service logs. For failures, inspect the failed step and
`report.json.error`, then correlate HTTP timings with `services.log`.

To enforce merge checks, protect `main` and require both `build-test` and
`docker-smoke`, including for administrators. **This private repository's
current GitHub plan blocks branch protection (API HTTP 403); failed CI alone
does not yet prevent merging.** See the [CI runbook](docs/day14-ci.md) for setup
and troubleshooting, and the [acceptance record](docs/day14-ci-summary.md) for
remote evidence and outstanding items.

---

## Planned Improvements

Future development will continue toward:

```text
Order workflow state management
Cache consistency and load testing
Distributed rate limiting
Integration testing
Kubernetes deployment
AWS deployment
Continuous deployment
```

---

## Project Goal

OrderFlow is intended as a portfolio-quality distributed systems project demonstrating practical backend engineering problems such as:

- reliable event delivery
- distributed workflow coordination
- database concurrency
- asynchronous communication
- failure recovery
- idempotency
- compensating transactions
- horizontal service scaling
- production-oriented observability and deployment
