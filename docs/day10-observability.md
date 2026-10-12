# Day 10: Observability and operator recovery

Day10 adds health probes, Prometheus business metrics, structured logs and tracing
to the Day9 order/inventory Saga. Executed results are generated in the
[Day10 summary](day10-observability-summary.md).

## Start the local monitoring stack

Start infrastructure and the four application services as described in the root
README, then start monitoring:

```powershell
docker compose -f docker-compose.observability.yml -p orderflow-observability up -d
```

Applications run on the host; Prometheus/Blackbox use `host.docker.internal` to
reach Gateway 9000, Order 8081 and Inventory 8082. Linux Compose includes a
host-gateway mapping. Local monitoring ports bind to 127.0.0.1.

| Component | URL | Purpose |
|---|---|---|
| Grafana | http://localhost:3000 | OrderFlow dashboard, metrics and trace data sources |
| Prometheus | http://localhost:9090 | Targets, queries and alert state |
| Zipkin | http://localhost:9411 | HTTP/Kafka traces |
| Alertmanager | http://localhost:9093 | Active alerts, grouping and silences |

Grafana login defaults to `admin` / `orderflow-local`; `GRAFANA_PASSWORD` overrides
the password when its data volume is first initialized. The dashboard and two
data sources are provisioned from `monitoring/grafana/`. Alertmanager's local
receiver exposes alerts in its UI; external notification delivery is an explicit
deployment configuration and is not enabled by this repository.

`ZIPKIN_ENDPOINT` changes the application span destination.
`TRACING_SAMPLING_PROBABILITY` defaults to 1.0 for local learning and validation.
Zipkin uses in-memory trace storage; Prometheus, Grafana and Alertmanager use
their own named volumes. Tracing is diagnostic and does not participate in the
business transaction.

## Health behavior

Order, Inventory and Gateway expose only `health`, `info` and `prometheus` over
Actuator HTTP. Database details and exception messages are hidden from health
responses. Application business ports must remain accessible to the local
monitoring containers. Add endpoint access control when deploying outside the
local environment.

| Endpoint | PostgreSQL unavailable | Kafka unavailable |
|---|---|---|
| `/actuator/health/liveness` | UP | UP |
| `/actuator/health/readiness` on business services | DOWN / 503 | UP |
| `/actuator/health` on business services | DOWN / 503 | DOWN / 503 |
| `/actuator/prometheus` | Scrapes cached metrics | Continues to serve metrics |

Readiness includes the database because HTTP operations require it. Kafka is
excluded from readiness because the Outbox lets an order commit during a broker
outage. Blackbox probes overall health so Kafka dependency failures remain
visible and alertable. Liveness deliberately excludes external dependencies.
Gateway probes describe its own process state; they do not guarantee a downstream
service is reachable.

## Metrics and semantics

Labels are bounded: application, status, state, transition, outcome and topic.
Order IDs, event IDs, SKUs, exception messages and trace IDs are kept in logs or
span tags rather than Prometheus labels.

| Prometheus metric | Meaning |
|---|---|
| `orderflow_outbox_events{status}` | Durable row counts: PENDING, PROCESSING, PUBLISHED, FAILED |
| `orderflow_outbox_oldest_age_seconds` | Age of the oldest event not yet published, including FAILED |
| `orderflow_outbox_retry_attempts` | Sum of persisted retry counts; operator retry can reduce it |
| `orderflow_outbox_attempts_total{outcome}` | Process publication attempts, success or failure |
| `orderflow_outbox_retries_total` | Attempts on rows already carrying a failure count |
| `orderflow_outbox_publish_seconds_*{outcome}` | Per-attempt publish/acknowledgement duration |
| `orderflow_saga_orders{state}` | Durable Saga row counts by state |
| `orderflow_saga_overdue` | Active Saga steps with an expired deadline |
| `orderflow_saga_transitions_total{from,to}` | State transitions recorded after commit |
| `orderflow_saga_timeouts_total` | Committed recovery handling of expired deadlines |
| `orderflow_saga_retries_total` | Committed automatic recovery retries |
| `orderflow_saga_duration_seconds_*{outcome}` | Creation-to-terminal duration for COMPLETED/CANCELLED |
| `orderflow_kafka_dlt_published_total{topic}` | Acknowledged DLT publications through this process's error handler |
| `orderflow_metrics_snapshot_available` | Last business metric refresh succeeded: 1; failed/uninitialized: 0 |
| `orderflow_metrics_snapshot_age_seconds` | Seconds since the last successful database snapshot |

Snapshots refresh every five seconds; configure
`orderflow.observability.snapshot-delay-ms` as needed. Scrapes never query the
database. Last good gauge values remain during outages, accompanied by availability
0 and increasing age. Gauges recover across JVM restarts. Counters/histograms reset
on restart; use Prometheus `rate`/`increase` and avoid treating them as an audit ledger.
With multiple instances, database gauges describe the same shared rows: use `max`
by application/status/state across instances, not a sum of identical snapshots.

Existing Outbox `created_at` columns contain local timestamps without a zone.
Backlog age interprets those timestamps in the JVM timezone by default;
`orderflow.observability.outbox-timestamp-zone` can specify a zone such as
`America/New_York`. All publishers sharing one database must use the same timezone.

DLT metrics cover application publications, not Kafka topic retention or messages
produced by an external replay tool. Saga transitions are recorded after commit,
so rollback and duplicate results do not count as business completions. Metrics
can still be lost if a JVM dies between a database commit and metric recording;
durable state gauges remain the source for recovery decisions.

## Logs and durable trace propagation

Spring Boot emits Logstash JSON. Workflow logs carry `orderId`, `eventId`,
`eventType`, state and reason alongside Micrometer's `traceId`/`spanId`.
Saga transitions and inventory command application are logged after commit.
Outbox logs include publication outcome and correlate to the restored trace.

HTTP and Kafka use W3C propagation. A normal request follows:

```text
Gateway HTTP -> Order HTTP -> persisted command Outbox
  -> restored outbox.publish span -> Kafka producer -> Inventory consumer
  -> persisted result Outbox -> Kafka -> Order consumer -> next command
  -> Inventory confirmation -> result -> Order completion
```

Order V5 and Inventory V6 add nullable `trace_parent` columns. New command/result
rows capture the active span in the same business transaction. Each publish
attempt extracts that parent, starts an `outbox.publish` span and lets Kafka
Observation propagate it to the listener. Producer observations omit optional
cluster-ID enrichment so observing a send cannot perform a synchronous broker
metadata lookup outside the configured send timeout. The context survives deferred execution,
failed delivery and a process restart. Existing payload JSON and Kafka event
contracts remain compatible; legacy rows with no parent start a new trace.

For a specific order, find its `orderId` in the JSON logs, copy `traceId`, and
search that ID in Zipkin (or the Grafana Zipkin data source). Outbox spans carry
`orderId` and `eventId` tags. Trace storage may expire independently of persisted
workflow data. Scheduled timeout recovery starts a new trace; use `orderId` to
correlate it with earlier workflow logs and traces.

## Alert diagnosis and recovery

Eight Prometheus rules live in `monitoring/prometheus/alerts.yml`. A dedicated
rule test file verifies pending/firing timing and counter-increase behavior.

| Alert | Trigger | Operator action |
|---|---|---|
| ServiceUnavailable | Scrape fails for 30s | Inspect service startup, endpoint and network connectivity |
| DependencyUnhealthy | Overall health probe fails for 30s | Check PostgreSQL/Kafka and service logs |
| OutboxFailed | FAILED rows persist for 10s | Inspect `last_error`, fix the cause, retry through the recovery API |
| OutboxBacklog | Oldest unpublished event >120s for 30s | Check broker, workers, stale claims and exhausted attempts |
| SagaManualRecovery | MANUAL_RECOVERY rows persist for 10s | Inspect Saga reason/recovery state, then request recovery |
| SagaOverdue | Expired active steps persist for 30s | Inspect consumers and recovery scheduler |
| DltMessages | DLT publication counter increases within 5m | Inspect original record and exception; correct before replay |
| BusinessMetricsUnavailable | Snapshot failed or age >30s for 30s | Check database and snapshot exception logs |

Inspect the correct service database before issuing recovery:

```sql
SELECT id, aggregate_id, event_type, status, retry_count, last_error, trace_parent
FROM outbox_events WHERE status = 'FAILED' ORDER BY created_at;

-- Order database:
SELECT order_id, state, recovery_state, failure_reason, retry_count, deadline
FROM order_sagas WHERE state = 'MANUAL_RECOVERY';
```

Use Gateway recovery routes (or the matching business-service port):

```http
GET  http://localhost:9000/api/orders/{orderId}/saga
POST http://localhost:9000/api/orders/{orderId}/saga/retry
POST http://localhost:9000/api/orders/outbox/{eventId}/retry
POST http://localhost:9000/api/inventory/outbox/{eventId}/retry
```

Resume a Saga only after inspecting its durable state. Saga retry creates a new
command ID; Outbox retry requeues the original ID. Retry of an obsolete command
returns 409. DLT records require deliberate inspection/replay, as described in
the [Day9 recovery notes](day9-saga-workflow.md). Once the cause is fixed, verify
the order reaches its expected terminal state, backlog decreases and alerts resolve.

## Reproduce validation

```powershell
powershell -File scripts/run-day10-tests.ps1
# Optional repository and infrastructure retention:
powershell -File scripts/run-day10-tests.ps1 -MavenRepository C:/Users/marj-/.m2/repository -KeepInfrastructure
python scripts/summarize-day10.py
```

The observability suite also saves successful trace and firing-alert API responses
as JSON beside the service logs for inspection after monitoring containers stop.

The runner builds services first, validates Prometheus configuration/rules, then
executes Day9 regression and Day10 observability in separate fresh disposable
environments. Reports and check evidence go to
`integration-tests/target/day10-reports`; service JSON logs go to
`integration-tests/target/service-logs`. It generates the Day10 summary from
those artifacts and leaves the original Day9 summary unchanged.

Projects `orderflow-it` and `orderflow-day10` are reserved for these tests.
The runner resets their containers/volumes, and cleans them up unless
`-KeepInfrastructure` is passed. Development project containers and databases
are not used. The test Gateway uses direct localhost routes, so Eureka discovery
and production load balancing remain outside this suite.

Monitoring test ports: Prometheus 19090, Grafana 13000, Zipkin 19411, Alertmanager
19093 and Blackbox 19115. The runner uses the same alert rules, dashboard and
data sources as local monitoring, with faster scrape/snapshot intervals.

References: [Spring Boot tracing](https://docs.spring.io/spring-boot/reference/actuator/tracing.html),
[Spring Kafka Observation](https://docs.spring.io/spring-kafka/reference/kafka/micrometer.html),
[Grafana provisioning](https://grafana.com/docs/grafana/latest/administration/provisioning/),
[Prometheus rule testing](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/).
