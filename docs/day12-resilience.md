# Day 12: Gateway HTTP resilience

All **14 required tasks** are completed. The fifteenth checklist item, rate
limiting, is optional and deferred as requested. On 2026-10-14, **79/79 tests
passed**, including 14 Day12 tests and all 65 Day9-Day11 checks, with no failures,
errors or skips. See the [final test summary](day12-resilience-summary.md).

## Delivery checklist

- [x] Inspect Gateway HTTP and independent Kafka/Saga boundaries.
- [x] Integrate compatible reactive Resilience4j dependencies/configuration.
- [x] Configure CLOSED/OPEN/HALF_OPEN circuit breakers.
- [x] Bound connection and response waits.
- [x] Restrict bounded retry/backoff to GET.
- [x] Return standard 503/504 errors without fabricated order success.
- [x] Add fail-fast per-route concurrency protection.
- [ ] Optional rate limiting: deferred; not a completion requirement.
- [x] Test circuit opening and recovery.
- [x] Test timeouts, backoff and actual attempt counts.
- [x] Test real Saga consistency after lost responses and HTTP faults.
- [x] Expose metrics and provision/verify the Grafana dashboard.
- [x] Run all Day9-Day11 regression suites.
- [x] Update runbook, README and generated test summaries.
- [x] Commit Day12 delivery to local Git history.

## Boundaries

Gateway routes synchronous HTTP requests to Order and Inventory, using Eureka
load balancing in development. The test Gateway uses direct HTTP addresses.
Order creation commits an order, Saga and command Outbox in PostgreSQL; Kafka
then drives inventory reservation, confirmation or compensation independently.
Gateway circuit state never changes an order or a Saga. Kafka retry, DLT,
consumer idempotency, Outbox retry and operator recovery retain their existing
responsibilities. Redis remains an optional HTTP read cache with database fallback.

## Implementation

The Spring Cloud 2025.1.3 BOM selects CircuitBreaker/Gateway 5.0.3 and
Resilience4j 2.3.0 for the project's Spring Boot 4.1.1. Gateway uses
`spring-cloud-starter-circuitbreaker-reactor-resilience4j` and
`resilience4j-micrometer`; no unmanaged library upgrade or imperative AOP
annotation is required. Actual packaged Gateway startup and HTTP tests verify
compatibility; compilation alone is not the compatibility check.

Each route installs the `Resilient` filter. The order is a fail-fast semaphore
bulkhead, circuit breaker, GET retry and downstream HTTP attempt. Circuit
breakers are named `order-service` and `inventory-service` and have independent
state. The bulkhead runs before Netty response writing and releases its permit
on completion, cancellation or error, including synchronous filter errors.
There is no waiting queue for saturated route permits. Spring Cloud's automatic
bulkhead wrapping is disabled to avoid adding another hidden queue.

| Setting | Default | Configuration |
|---|---|---|
| Failure rate | 50% | `GATEWAY_CB_FAILURE_RATE` |
| Minimum calls | 10 | `GATEWAY_CB_MIN_CALLS` |
| Count window | 20 | `resilience4j.circuitbreaker.configs.default.sliding-window-size` |
| OPEN wait | 10s | `GATEWAY_CB_OPEN_WAIT` |
| HALF_OPEN probes | 3 | `GATEWAY_CB_HALF_OPEN_CALLS` |
| Connection timeout | 1000ms | `GATEWAY_CONNECT_TIMEOUT_MS` |
| Response timeout per attempt | 3s | `GATEWAY_RESPONSE_TIMEOUT` |
| Total route time limit | 7s | `GATEWAY_TOTAL_TIMEOUT` |
| GET attempts including first | 2 | `GATEWAY_GET_MAX_ATTEMPTS` (1..5) |
| Retry backoff | 200ms, factor 2, maximum 8 times base | `GATEWAY_RETRY_BACKOFF` |
| Concurrent calls per route/instance | 64 | `GATEWAY_MAX_CONCURRENT_CALLS` |
| Connection pool | 128, acquire timeout 1000ms | Gateway HTTP client pool configuration |

After the OPEN wait, incoming requests test HALF_OPEN; successful probes close
the circuit. Transport failures and HTTP 500/502/503/504 feed the breaker.
Business 400/404/409 responses are passed through and are not retried.
GET retries apply only to configured transient statuses and transport/timeout
exceptions. A logical GET can take about 6.2s across two 3s response waits and
backoff; the separate 7s time limit bounds the wrapped operation. Tune the
overall budget with attempt count, backoff and connection/response timeouts.

POST, PUT, DELETE and other methods are not automatically retried. Reactor
Netty's implicit connection-reset retry is also disabled. Retrying a POST at
any layer could create a second order after the first commit succeeded but its
response was lost. Gateway does not implement client-level idempotency keys.
A 503/504 after a write is therefore an ambiguous outcome: inspect durable
business state/logs before deciding to submit another write.

## Errors and metrics

Fallback returns JSON with `status`, `code`, `message`, `route`, `timestamp`
and `requestId`, with `Cache-Control: no-store`. It never returns an order ID,
HTTP 201 or a fabricated successful order. Exception internals are not exposed.

| Code | HTTP status |
|---|---:|
| `DOWNSTREAM_UNAVAILABLE` | 503 |
| `CIRCUIT_OPEN` | 503 |
| `CONCURRENCY_LIMIT` | 503 |
| `DOWNSTREAM_TIMEOUT` | 504 |

The fallback handler requires a circuit execution exception on the exchange;
direct external requests to its internal path receive 404. If a response is
already committed, it cannot be replaced with fallback JSON. These APIs use
small JSON responses; streaming/partial response behavior is not a delivery
guarantee. A timeout does not cancel a database transaction already committed
or guarantee that the upstream operation stopped.

Prometheus exposes Resilience4j circuit state/failure/call metrics and
`orderflow_gateway_attempts_total`, `orderflow_gateway_retries_total`,
`orderflow_gateway_timeouts_total`, `orderflow_gateway_rejected_total`,
`orderflow_gateway_fallbacks_total` and
`orderflow_gateway_concurrent_available`. Timeout counters count final logical
timeout responses; retries count actual additional dispatch attempts. Labels
are bounded route names, GET/OTHER and fixed error codes, never order IDs/SKUs.
Counters reset on Gateway restart; circuit state and concurrency are local to
each Gateway instance. Saturation does not count as a downstream breaker failure.
Failure rate is -1 before the minimum sample count is reached.

The separate `OrderFlow Gateway resilience` Grafana dashboard provisions six
panels: circuit state, failure rate, retries, timeouts, rejections and available
permits. The existing Day10 dashboard remains intact.

Rate limiting is optional and deferred. This release's concurrency protection
rejects saturation with 503; it does not promise a rate quota or 429 response.

## Validation and reproduction

```powershell
powershell -File scripts/run-day12-tests.ps1 -RunRegressions
```

The runner builds packaged services, executes semaphore unit tests and a
controlled real HTTP downstream suite, then real PostgreSQL/Kafka/Redis Saga
tests with fault proxies. It runs the existing Day11 cache suite and Day9/Day10
regressions afterward, using disposable test projects and removing them after
use. Development containers/databases are not reset. The HTTP fixture uses
ports 18084/18085; Gateway 19000 and business services 18081/18082 are reserved
for the tests. The suites run sequentially, including builds and service restarts.

`-SkipHttpSuite` is for reusing an already passing HTTP report during local
iteration; full reproduction does not use it. XML/check evidence is written to
`integration-tests/target/day12-reports`; service logs and exported Gateway
metrics are in `integration-tests/target/service-logs`. Existing regression
reports retain their Day10/Day11 directories. The final summary requires fresh,
passing reports and regression completion after Day12 verification.

The real Saga suite deliberately loses a committed POST response (503 and
connection reset), verifies a single created order/reservation/stock deduction,
retries a temporary GET, replays a duplicate Kafka result, isolates an Inventory
HTTP circuit from Kafka processing and stops/restarts Order Service. Existing
regressions cover Redis/Kafka outages, stock rollback, compensation and recovery.
Eureka registration, multi-broker failover, production load and distributed rate
limiting remain outside these tests.

References: [Gateway CircuitBreaker](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/circuitbreaker-filter-factory.html),
[Gateway Retry](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/retry-factory.html),
[HTTP timeouts](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/http-timeouts-configuration.html).
