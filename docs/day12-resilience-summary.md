# Day 12 resilience test summary

Generated: 2026-10-14T17:02:55-04:00
Day12 verification completed: 2026-10-14T20:26:35.0483582Z
Day9-Day11 regression completed: 2026-10-14T20:58:42.7270126Z

Result: **79/79 passed**, failures: 0, errors: 0, skipped: 0.

All required development/verification tasks are implemented; Git delivery is recorded in repository history.
Rate limiting is optional and deferred, as requested; no 429/quota guarantee is claimed.

Service build: **PASS**; Prometheus configuration: **PASS**;
Prometheus alert rule tests: **PASS**.

| Suite | Passed | Seconds |
|---|---:|---:|
| Day12 concurrency unit | 3/3 | 4.437 |
| Day12 real HTTP faults | 7/7 | 38.049 |
| Day12 real Saga/fault proxies | 4/4 | 172.167 |
| Day11 order cache | 10/10 | 9.487 |
| Day11 inventory cache | 10/10 | 7.485 |
| Day11 mutation wiring | 6/6 | 3.519 |
| Day11 real Redis | 1/1 | 5.574 |
| Day11 HTTP/Kafka/Redis | 9/9 | 388.691 |
| Day9 Saga regression | 20/20 | 882.028 |
| Day10 observability + Day12 dashboard | 9/9 | 422.227 |

## Day12 fault scenarios

| Scenario | Result | Seconds |
|---|---|---:|
| cancellationReleasesPermission | PASS | 3.182 |
| synchronousDownstreamErrorDoesNotLeakPermission | PASS | 0.058 |
| saturationDoesNotDispatchOrWaitAndReleaseIsBalanced | PASS | 0.319 |
| circuitsOpenIndependentlyAndRecoverThroughThreeHalfOpenProbes | PASS | 14.470 |
| normalRoutesAndBusiness400ArePassedThrough | PASS | 0.019 |
| getRetriesOnceWithBackoffThenSucceeds | PASS | 0.214 |
| postFailureAndConnectionResetAreNeverRetried | PASS | 0.020 |
| postTimeoutHasOneAttemptAndGetTimeoutHasTwo | PASS | 9.237 |
| concurrencyRejectsImmediatelyAndReleasesPermits | PASS | 0.018 |
| prometheusExposesBoundedResilienceMetrics | PASS | 0.047 |
| lostCreateResponseDoesNotReplayPostOrDuplicateStockDeduction | PASS | 3.030 |
| transientGetAndDuplicateKafkaResultCannotChangeCompletedWorkflow | PASS | 1.380 |
| inventoryHttpCircuitDoesNotMutateStockOrStopIndependentKafkaSaga | PASS | 1.493 |
| stoppedOrderHttpOpensCircuitWithoutCreatingOrdersThenRecovers | PASS | 50.971 |

## Evidence and operating limits

- Packaged Gateway JVM and controlled real HTTP servers verify circuit states, ten-failure threshold,
  ten-second OPEN recovery window, three HALF_OPEN probes, bounded retries/backoff, POST no-replay
  (including connection reset), timeouts, concurrency rejection and Prometheus metrics.
- Real Order/Inventory/PostgreSQL/Kafka/Redis and HTTP fault proxies verify one order/reservation/stock
  deduction after a committed POST response is lost, duplicate Kafka-result idempotency, HTTP Inventory
  circuit isolation from Kafka Saga processing and actual Order Service stop/restart recovery.
- Day11 regressions exercise Redis outage and recovery; Day9/Day10 cover Kafka/database failure,
  compensation, Outbox, DLT, recovery, tracing, metrics and alerts. Their original scenario counts remain unchanged.
- Live Grafana API validates the new six-panel resilience dashboard and the existing twelve-panel dashboard.
- Development project orderflow is not reset. Disposable orderflow-it/orderflow-day11/orderflow-day10
  projects and service JVMs are cleaned up. Builds and suites run sequentially.
- IntelliJ debugger router tools were unavailable. Runtime diagnostics used actual HTTP attempt counts
  and exported circuit metrics; timeout exception matching was checked against Gateway 5.0.3 source.
- No POST automatic retry or business status mutation by fallback. A 503/504 after an upstream commit
  remains an ambiguous write outcome, not proof of rollback; client idempotency keys are outside this release.
- Circuit state and concurrency limits are per Gateway instance. Streaming responses already committed
  cannot be replaced with fallback JSON. Eureka discovery/load balancing, load tests and distributed rate limiting are not covered.

## Reports and reproduction

```powershell
powershell -File scripts/run-day12-tests.ps1 -RunRegressions
```

Raw XML evidence:

- `integration-tests/target/day12-reports/TEST-com.rom.orderflow.gateway.resilience.GatewayConcurrencyFilterTest.xml`
- `integration-tests/target/day12-reports/TEST-com.rom.orderflow.integration.GatewayResilienceIntegrationTest.xml`
- `integration-tests/target/day12-reports/TEST-com.rom.orderflow.integration.ResilienceSagaIntegrationTest.xml`
- `integration-tests/target/day11-reports/TEST-com.rom.orderflow.order.cache.OrderReadCacheTest.xml`
- `integration-tests/target/day11-reports/TEST-com.rom.orderflow.inventory.cache.InventoryReadCacheTest.xml`
- `integration-tests/target/day11-reports/TEST-com.rom.orderflow.inventory.cache.InventoryCacheInvalidationTest.xml`
- `integration-tests/target/day11-reports/TEST-com.rom.orderflow.inventory.cache.InventoryRedisIntegrationTest.xml`
- `integration-tests/target/day11-reports/TEST-com.rom.orderflow.integration.RedisCacheIntegrationTest.xml`
- `integration-tests/target/day10-reports/TEST-com.rom.orderflow.integration.SagaWorkflowIntegrationTest.xml`
- `integration-tests/target/day10-reports/TEST-com.rom.orderflow.integration.ObservabilityIntegrationTest.xml`

Service logs/metrics: `integration-tests/target/service-logs/`.
[Day12 runbook](day12-resilience.md), [Day11 regression summary](day11-redis-cache-summary.md),
[Day9 summary](day9-integration-test-summary.md), [Day10 summary](day10-observability-summary.md).
