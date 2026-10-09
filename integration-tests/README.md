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
