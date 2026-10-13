# Day 11: Redis order and inventory read caches

Day11 adds optional Redis caches to `GET /api/orders/{id}` and
`GET /api/inventory/{sku}`. PostgreSQL remains authoritative for order state,
stock, reservations and Saga decisions. The
[final test summary](day11-redis-cache-summary.md) records the executed cache,
HTTP/Kafka/Redis and Day9/Day10 regression results.

## Completed tasks

- [x] Connect the Order Service GET endpoint to a Redis cache.
- [x] Set the default order cache TTL to 45 seconds.
- [x] Invalidate order cache after Saga state updates commit.
- [x] Run real HTTP/Kafka/Redis end-to-end tests.
- [x] Re-run Day9 Saga regression, then Day10 observability regression.
- [x] Generate the final Day11 test summary and update README.

## Local infrastructure and configuration

Redis belongs to the existing `orderflow` Compose project alongside PostgreSQL
and Kafka. Applications continue to run on the host, as in Day10.

```powershell
docker compose -p orderflow up -d --no-deps --wait redis
docker exec orderflow-redis redis-cli ping
# Expected: PONG
```

Redis 7 binds to `127.0.0.1:6379`, has a PING health check and uses AOF
persistence on the existing `redis_data` volume. Starting Redis does not reset
development databases. Build and restart Order and Inventory Services to use
the new application code.

| Environment variable | Default | Meaning |
|---|---|---|
| `REDIS_HOST` | `localhost` | Shared Redis host for host-run services |
| `REDIS_PORT` | `6379` | Redis port |
| `ORDER_CACHE_ENABLED` | `true` | Enable Order HTTP cache and Redis health indicator |
| `ORDER_CACHE_TTL` | `45s` | Positive order expiration duration |
| `INVENTORY_CACHE_ENABLED` | `true` | Enable Inventory HTTP cache and Redis health indicator |
| `INVENTORY_CACHE_TTL` | `5s` | Positive inventory expiration duration |

Connect and command timeouts are both 300ms. They bound individual Redis
operations, not complete HTTP request duration including database access.

## Order query and Saga invalidation

The existing `GET /api/orders/{id}` endpoint now uses cache-aside outside the
database transaction: read Redis, return a hit, otherwise load PostgreSQL and
store the response as JSON with a 45-second expiration. Keys are
`orderflow:order:v1:{id}`. The response includes order/customer IDs, status,
amount, currency, optimistic-lock version and timestamps. Missing orders return
404 and are not cached; invalid UUIDs return 400.

Saga transitions register eviction with the surrounding Spring transaction's
`afterCommit` callback. This covers creation, Kafka reservation/confirmation/
release results, HTTP cancellation, scheduled timeout compensation and operator
recovery transitions. A rolled-back transaction never executes eviction.
Redis eviction errors cannot fail a business operation whose database transaction
has already committed. Saga status/recovery queries and business decisions
continue to read PostgreSQL directly.

## Inventory query and mutation invalidation

Inventory uses the same read behavior with keys
`orderflow:inventory:v1:{sku}` and a five-second expiration. Create, update,
reserve, confirm and release register eviction after the actual surrounding
database transaction commits. Kafka Saga operations reuse those stock methods,
so a multi-item transaction rollback does not trigger eviction.

Neither cache stores database errors or missing entities. Unreadable JSON and
Redis connection/command errors fall back to the database. A failed Redis read
skips the cache write for that request. A failed cache write cannot fail a
successful database read. Cached responses must match the requested order ID
or SKU; mismatched payloads are replaced from PostgreSQL. String/JSON
serialization avoids Java object serialization, and inventory timestamps retain
their original offset during cache deserialization.

## Consistency and health

These HTTP responses are eventually consistent. A concurrent read can fetch an
old database value and populate it after another transaction's eviction.
Failed eviction, direct SQL writes or Redis persistence can also leave an older
response. Expiration bounds each stored entry's lifetime; it is not a strict
freshness bound measured from a database commit. The 45-second order TTL is
the initial setting requested for Day11, not a guarantee of immediate status
visibility under concurrent reads or dependency failure.

Set `ORDER_CACHE_ENABLED=false` or `INVENTORY_CACHE_ENABLED=false` when the
corresponding HTTP endpoint must read PostgreSQL directly. Clients must not use
displayed stock to decide whether a reservation will succeed. Saga and stock
decisions always use database transactions and existing concurrency controls.

With the relevant cache enabled, Redis failure makes that service's overall
health DOWN while liveness and database readiness remain UP. HTTP operations
and Kafka workflow processing continue through PostgreSQL. Disabling a cache
through its environment variable also disables that service's Redis health
indicator. Day10 overall health probes can report the optional cache outage.

## Metrics

Prometheus exposes `orderflow_order_cache_requests_total{outcome}` and
`orderflow_inventory_cache_requests_total{outcome}` with bounded outcomes
`hit`, `miss`, `read_error`, `write_error`, `eviction` and `eviction_error`.
Order IDs and SKUs never become metric labels. Counters reset on JVM restart.

## Reproduce complete validation

Run these commands in order:

```powershell
powershell -File scripts/run-day11-tests.ps1
powershell -File scripts/run-day10-tests.ps1 -RefreshDay9Summary
python scripts/summarize-day11.py
```

The Day11 runner builds Order, Inventory and Gateway, runs 27 cache unit/wiring/
Redis tests, then runs nine end-to-end scenarios with real HTTP, JDBC, Kafka and
Redis. It uses disposable projects `orderflow-day11` (Redis 16379) and
`orderflow-it` (PostgreSQL 15432, Kafka 19092), then removes them. It does not
connect to development PostgreSQL/Kafka or flush development Redis.

End-to-end checks cover cache hit/404 behavior, an actual 45-second expiration,
service restart, Kafka commit invalidation, HTTP cancellation and compensation,
transaction rollback and DLT replay, corrupt/mismatched payloads, Redis outage
and recovery, inventory HTTP mutation, and scheduled timeout invalidation.
The restart test extends only its cached fixture to five minutes during cold
startup, then restores 45 seconds; application TTL configuration stays 45s.

The second command runs all 20 Day9 Saga scenarios, then all nine Day10
observability scenarios in fresh business environments. It validates Prometheus
configuration and eight alert rules, and refreshes both regression summaries.
These older suites explicitly disable both Redis caches and Redis health checks
to retain their PostgreSQL/Kafka isolation; Redis failure behavior is tested in
the Day11 suite. The standard Day10 runner preserves the Day9 summary unless
`-RefreshDay9Summary` is supplied.

The summary generator requires passing reports and a regression completion time
after the cache suite. Reports/check evidence live in
`integration-tests/target/day11-reports` and `integration-tests/target/day10-reports`;
service JSON logs and saved trace/alert evidence live in
`integration-tests/target/service-logs/run-*`. Gateway uses direct test routes;
Eureka registration and production load balancing are outside these suites.

On 2026-10-13, **65/65 tests passed**, with no failures, errors or skipped tests:
36 Day11 checks, 20 Day9 checks and nine Day10 checks. See the
[final summary](day11-redis-cache-summary.md) for per-suite and scenario results.

## Future improvements

Version fencing against concurrent stale refill, cache stampede mitigation,
load testing and dedicated Grafana cache panels remain future work.

References: [Spring StringRedisTemplate](https://docs.spring.io/spring-data/redis/docs/current/api/org/springframework/data/redis/core/StringRedisTemplate.html),
[Spring transaction synchronization](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html).
