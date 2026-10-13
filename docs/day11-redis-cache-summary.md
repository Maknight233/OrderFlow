# Day 11 final test summary

Generated: 2026-10-13T19:13:35-04:00
Day11 validation completed: 2026-10-13T22:49:04.8117848Z
Day9/Day10 regression completed: 2026-10-13T23:11:15.4708428Z

Result: **65/65 passed**, failures: 0, errors: 0, skipped: 0.

## Completed in requested order

- [x] GET `/api/orders/{id}` uses an optional Redis cache; PostgreSQL remains authoritative.
- [x] Order cache defaults to **45 seconds** (`ORDER_CACHE_TTL`); inventory cache remains 5 seconds.
- [x] Saga transitions register order cache eviction after transaction commit, including Kafka results,
  HTTP cancellation and scheduled timeout compensation. Rolled-back transactions do not evict.
- [x] Run real Gateway HTTP/PostgreSQL/Kafka/Redis end-to-end tests.
- [x] Re-run Day9 Saga regression, then Day10 observability regression.
- [x] Update this final summary, Day11 runbook and README.

Service build: **PASS**; Prometheus configuration: **PASS**;
Prometheus alert rule tests: **PASS**.

| Suite | Passed | Seconds |
|---|---:|---:|
| Order cache unit/configuration | 10/10 | 15.974 |
| Inventory cache unit/configuration | 10/10 | 8.380 |
| Inventory mutation wiring | 6/6 | 3.479 |
| Real Redis cache | 1/1 | 4.919 |
| HTTP/Kafka/Redis end-to-end | 9/9 | 403.488 |
| Day9 regression | 20/20 | 835.422 |
| Day10 regression | 9/9 | 361.390 |

## End-to-end scenarios

| Scenario | Result | Seconds |
|---|---|---:|
| gatewayReadsCacheWith45SecondTtlAndDoesNotCache404 | PASS | 46.626 |
| kafkaCommitInvalidatesPendingOrderAndInventoryCaches | PASS | 48.253 |
| httpCancellationAndKafkaReleaseInvalidateEachCommittedStatus | PASS | 41.215 |
| rolledBackSagaPreservesCacheThenReplayInvalidatesIt | PASS | 41.589 |
| corruptOrWrongOrderPayloadFallsBackToPostgres | PASS | 1.775 |
| actual45SecondExpirationReloadsAndRefreshesTtl | PASS | 46.809 |
| redisOutageAllowsReadsAndKafkaSagaThenRecovers | PASS | 7.088 |
| inventoryHttpUpdateAndShortTtlRemainUsable | PASS | 0.166 |
| scheduledTimeoutInvalidatesPendingCacheBeforeCompensationCompletes | PASS | 46.784 |

## Environment and evidence

- Java 21, Spring Boot 4.1.1, Redis 7, PostgreSQL 16 and Kafka 4.2.1.
- Packaged Order, Inventory and Gateway JVMs; real HTTP/JDBC/Kafka/Redis. Gateway uses direct test routes;
  Eureka registration and production load balancing are outside these suites.
- Disposable projects `orderflow-it` (PostgreSQL 15432, Kafka 19092), `orderflow-day11` (Redis 16379),
  and `orderflow-day10` (monitoring). Development project `orderflow` is not reset or used by tests.
- Actual 45-second expiration is awaited. The separate restart test extends only its Redis fixture
  to five minutes during cold startup, then restores 45 seconds; application TTL configuration stays 45s.
- Order rollback is injected with a PostgreSQL trigger. DLT is observed by order ID, then replayed;
  rollback preserves the cache and commits neither the inbox record nor the confirmation Outbox command.
- Redis failure is injected by stopping the dedicated container. Database-backed reads and Kafka Saga
  completion continue; readiness/liveness stay UP, overall health reports the cache dependency failure.
- Cache corruption/wrong-ID payloads, cache hit metrics, HTTP mutation invalidation, Kafka compensation
  and scheduled timeout invalidation are exercised. Inventory recovers fresh values after outage expiration.
- Service JSON logs: `integration-tests/target/service-logs/run-*/`; runner logs: `integration-tests/target/day11-run.log`
  and `integration-tests/target/day11-regression-run.log`.

XML reports used for this summary:

- `integration-tests/target/day11-reports/TEST-com.rom.orderflow.order.cache.OrderReadCacheTest.xml`
- `integration-tests/target/day11-reports/TEST-com.rom.orderflow.inventory.cache.InventoryReadCacheTest.xml`
- `integration-tests/target/day11-reports/TEST-com.rom.orderflow.inventory.cache.InventoryCacheInvalidationTest.xml`
- `integration-tests/target/day11-reports/TEST-com.rom.orderflow.inventory.cache.InventoryRedisIntegrationTest.xml`
- `integration-tests/target/day11-reports/TEST-com.rom.orderflow.integration.RedisCacheIntegrationTest.xml`
- `integration-tests/target/day10-reports/TEST-com.rom.orderflow.integration.SagaWorkflowIntegrationTest.xml`
- `integration-tests/target/day10-reports/TEST-com.rom.orderflow.integration.ObservabilityIntegrationTest.xml`

## Consistency limits

This is an eventually consistent read cache. A concurrent database read can repopulate an old value
after another transaction evicts it. Failed eviction, direct SQL writes and Redis persistence can also
leave an older cached response. TTL bounds each stored entry's lifetime, not freshness from a commit.
Saga/stock decisions always read PostgreSQL. Disable the relevant cache for authoritative HTTP reads.
Version fencing, cache stampede mitigation, load testing and dedicated cache dashboard panels remain future work.

## Reproduction

```powershell
powershell -File scripts/run-day11-tests.ps1
powershell -File scripts/run-day10-tests.ps1 -RefreshDay9Summary
python scripts/summarize-day11.py
```

[Day11 runbook](day11-redis-cache.md), [Day9 regression summary](day9-integration-test-summary.md),
[Day10 regression summary](day10-observability-summary.md).
