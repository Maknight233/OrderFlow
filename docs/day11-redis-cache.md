# Day 11: Redis inventory read cache

Day11 starts with an optional Redis cache for `GET /api/inventory/{sku}`.
PostgreSQL remains authoritative for stock, reservations and Saga decisions.
This is the first implementation milestone; concurrency fencing, load testing
and dedicated Grafana panels remain follow-up work.

## Local infrastructure

Redis is part of the existing `orderflow` Compose project alongside PostgreSQL
and Kafka. Services continue to run on the host, as in Day10.

```powershell
docker compose -p orderflow up -d --no-deps --wait redis
docker exec orderflow-redis redis-cli ping
# Expected: PONG
```

Redis 7 binds to `127.0.0.1:6379`, has a PING health check and enables AOF
persistence on the existing `redis_data` volume. Starting this service does not
reset the development databases. Restart Inventory Service after building the
new application code to enable the cache.

| Environment variable | Default | Meaning |
|---|---|---|
| `REDIS_HOST` | `localhost` | Redis host for the host-run service |
| `REDIS_PORT` | `6379` | Redis port |
| `INVENTORY_CACHE_ENABLED` | `true` | Enable HTTP cache and Redis health indicator |
| `INVENTORY_CACHE_TTL` | `5s` | Positive expiration duration |

Connect and command timeouts are both 300ms. These bound individual Redis
operations, not complete HTTP request duration including database access.

## Read and write behavior

The controller uses cache-aside: read Redis, return a hit, otherwise load the
database and store the response as JSON with an expiration. Keys are
`orderflow:inventory:v1:{sku}`. The version prefix supports future payload changes.
Missing stock and database errors are not cached. Unreadable JSON and Redis
connection/command errors fall back to the database. A failed Redis read skips
the cache write for that request. A failed cache write cannot fail a successful
database read.

Create, update, reserve, confirm and release register cache eviction after the
actual surrounding database transaction commits. Saga operations reuse those
same stock methods, so multi-item Saga rollback does not trigger eviction.
Redis eviction failure cannot turn an already committed stock operation into
an HTTP/Kafka failure. Business logic reads the database directly.

The HTTP response is eventually consistent. A concurrent read can fetch an old
database value, then populate it after another transaction's eviction. A failed
eviction, direct SQL write or Redis restart can also leave an older response.
Expiration limits each stored entry's lifetime; it is not a strict freshness
bound measured from a database commit. Clients must not use the displayed
quantity to decide whether a reservation will succeed. Set
`INVENTORY_CACHE_ENABLED=false` when current database reads are required.

Redis failure leaves liveness and database readiness UP, while Inventory's
overall health becomes DOWN through the Redis health indicator. Day10 overall
health probes can therefore report an optional cache outage even while business
operations continue. Disabling the cache also disables that indicator through
the environment variable above.

## Metrics

Prometheus exposes `orderflow_inventory_cache_requests_total{outcome}` with
bounded outcomes `hit`, `miss`, `read_error`, `write_error`, `eviction` and
`eviction_error`. SKUs never become metric labels. Counters reset on JVM restart.

## Reproduce first-milestone validation

```powershell
powershell -File scripts/run-day11-tests.ps1
```

The runner uses a dedicated `orderflow-day11` Redis container on port 16379,
runs cache unit tests, mutation wiring tests and a real Redis test, then removes
that test container. It does not connect to development PostgreSQL/Kafka or
flush development Redis. The real Redis test uses a random SKU and checks JSON
round-trip, cache hit, expiration and eviction. Unit tests cover outages,
corrupt payloads, database errors, disabled caching, commit and rollback.
Reports are saved in `inventory-service/target/surefire-reports`.

Validation on 2026-10-13: 10 cache/configuration unit tests, 6 mutation wiring
tests and 1 real Redis integration test passed (17 total). Inventory Service
packaging and both Compose configuration checks passed. Development Redis
returned PONG, reported healthy, and confirmed AOF enabled. Full HTTP/Kafka
cache fault injection and Day9/Day10 regression suites were not run in this milestone.

Day9/Day10 test processes explicitly disable Redis caching and its health
indicator to preserve their isolated PostgreSQL/Kafka test environments.
The existing Day9/Day10 fault suites are separate validation and are not run by
the Day11 runner.

## Next milestones

- Add multi-process HTTP/Kafka tests for cache invalidation and Redis outage recovery.
- Evaluate version fencing to prevent old concurrent reads from repopulating cache.
- Measure query reduction and latency, then address cache stampedes if needed.
- Add cache hit/error panels and tune optional-cache alerts.

References: [Spring StringRedisTemplate](https://docs.spring.io/spring-data/redis/docs/current/api/org/springframework/data/redis/core/StringRedisTemplate.html),
[Spring transaction synchronization](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html).
