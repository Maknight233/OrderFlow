# Saga integration tests

The suite starts the two packaged services as separate JVMs and uses real
PostgreSQL and Kafka in the disposable Docker Compose project orderflow-it.
It never connects to the development databases or broker.

Prerequisites: Java 21, Maven, Docker Compose, and the postgres:16 and
apache/kafka:4.2.1 images.

On Windows, run from the repository root:

```powershell
powershell -File scripts/run-integration-tests.ps1
# Optional Maven cache override:
powershell -File scripts/run-integration-tests.ps1 -MavenRepository C:/Users/marj-/.m2/repository
```

The script builds the service jars with existing service tests skipped, resets
only the dedicated test Compose project, runs this suite, generates the summary
when Python is available, and removes test infrastructure. Use
-KeepInfrastructure to retain the dedicated containers for diagnosis.

To run the stages manually:

```powershell
mvn -pl order-service,inventory-service -am -DskipTests package
docker compose -f docker-compose.integration.yml -p orderflow-it down --volumes --remove-orphans
docker compose -f docker-compose.integration.yml -p orderflow-it up -d --wait --wait-timeout 120
mvn -f integration-tests/pom.xml test
python scripts/summarize-integration-tests.py
docker compose -f docker-compose.integration.yml -p orderflow-it down --volumes --remove-orphans
```

The suite resets only the four explicitly allowlisted test databases:
orderflow_it, inventory_it, orderflow_upgrade_it, inventory_upgrade_it.
Use fresh Compose infrastructure on each full run; Kafka topics and group
offsets belong to that run. No Docker volumes are shared with development.

Results:
- JUnit XML/text: target/surefire-reports/
- Service and broker logs: target/service-logs/
- Generated summary: ../docs/day9-integration-test-summary.md

The suite has 20 test cases covering schema upgrades, successful and rejected
orders, idempotency, reordered and late events, both cancellation race outcomes,
stock concurrency, persisted result delivery failure, broker outage, transaction
rollback, DLT/replay, timeout/manual recovery, abandoned claims, failed command
retry, PostgreSQL SKIP LOCKED, input validation, and legacy event compatibility.

Tests use shorter polling, five Outbox attempts (three for the intentional
result-publishing exhaustion), two Saga retries, and injected
past deadlines/abandoned claims to avoid waiting through production timeouts.
The public HTTP APIs, business transactions, serializers, Kafka listeners,
Outbox publishers, and Flyway migrations are real. Fault injection is confined
to the dedicated processes and databases.

The standard service tests currently depend on development infrastructure and
are excluded from this suite. Gateway/Eureka and multi-broker failover are also
outside its scope.

## Day10 observability validation

The default Maven suite remains `SagaWorkflowIntegrationTest`. The Day10 runner
also builds Gateway and starts a separate monitoring stack, then executes the
observability suite after resetting the disposable business infrastructure:

```powershell
powershell -File scripts/run-day10-tests.ps1
```

See [the observability runbook](../docs/day10-observability.md) for metric semantics,
ports and recovery. Day10 XML reports/check evidence are written to
`target/day10-reports`; [the Day10 summary](../docs/day10-observability-summary.md)
is generated from those reports. Day9's original summary is preserved.

Pass `-RefreshDay9Summary` to refresh the separate Day9 summary from this run.
The runner executes Day9 regression before Day10 observability, resetting the
business infrastructure between suites. Both caches and Redis health checks
are explicitly disabled in these regression processes.

## Day11 Redis cache validation

```powershell
powershell -File scripts/run-day11-tests.ps1
powershell -File scripts/run-day10-tests.ps1 -RefreshDay9Summary
python scripts/summarize-day11.py
```

The Day11 runner adds a dedicated Redis container on port 16379 and runs 27
cache tests plus nine real Gateway HTTP/PostgreSQL/Kafka/Redis scenarios.
The production order TTL is 45 seconds; inventory TTL is five seconds.
Reports/check evidence are saved to `target/day11-reports`; service logs and
trace/alert evidence remain in `target/service-logs`. Dedicated test containers
are removed afterward. See the [Day11 runbook](../docs/day11-redis-cache.md) and
[final summary](../docs/day11-redis-cache-summary.md) for coverage and consistency limits.

## Day12 Gateway resilience validation

```powershell
powershell -File scripts/run-day12-tests.ps1 -RunRegressions
```

This adds three concurrency unit tests, seven packaged-Gateway HTTP fault
scenarios and four real Saga/fault-proxy scenarios, then executes all Day11
cache tests and Day9/Day10 regressions. HTTP fixtures use 18084/18085;
Gateway uses 19000. Suites/builds run sequentially and dedicated test containers
are removed afterward. Rate limiting is optional and deferred.

Day12 reports/check evidence: `target/day12-reports`. The live Grafana check
also verifies the new six-panel resilience dashboard. See the
[Day12 runbook](../docs/day12-resilience.md) for retry safety, time budgets,
error codes and delivery limits.
