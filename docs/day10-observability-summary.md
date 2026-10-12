# Day 10 observability summary

Generated: 2026-10-12T15:21:46-04:00
Verification completed: 2026-10-12T19:21:46.1266214Z

Result: **29/29 passed**, failures: 0, errors: 0, skipped: 0.
Suite elapsed time: **1361.616 seconds** (includes service startup/restarts).

Scope: health probes, Prometheus business metrics, structured logs, durable
HTTP/Kafka tracing, Grafana dashboards, Prometheus alerts and operator recovery.

## Implemented

- Order, Inventory and Gateway expose health, info and Prometheus endpoints.
  Liveness checks process availability; business-service readiness also checks PostgreSQL.
  Kafka failure makes overall health DOWN while readiness remains UP, allowing durable Outbox writes.
- Database-backed gauges track Outbox status, retries and backlog age, Saga state and overdue steps.
  Cached snapshots keep scrapes responsive during database outages; availability and age expose stale data.
- Process counters/timers track publication outcome, retry attempts, acknowledged DLT publication,
  committed Saga transitions, step timeouts, recovery retries and total terminal workflow duration.
  Saga metrics are recorded after transaction commit; duplicate messages do not inflate completion metrics.
- JSON logs include orderId, eventId, eventType, state, reason, traceId and spanId at workflow boundaries.
- W3C traceparent is persisted on both Outboxes (Order V5, Inventory V6) and restored per attempt.
  Gateway HTTP, delayed Kafka commands and inventory results share one trace, including after process crashes.
- Local Compose stack provisions Prometheus, Grafana, Zipkin, Alertmanager and Blackbox Exporter.
  Grafana provisions 12 panels and Prometheus/Zipkin data sources; eight alert rules include FAILED,
  MANUAL_RECOVERY, new DLT messages, dependency failures, backlog and stale metric snapshots.

## Executed validation

Service build: **PASS**.
Prometheus configuration: **PASS**; alert rule tests: **PASS**.

### Day9 regression

**20/20 passed**, failures: 0, errors: 0, skipped: 0.
Elapsed: **874.306 seconds**, including process startup/restarts.

| Scenario | Result | Seconds |
|---|---|---:|
| freshAndLegacyFlywayMigrationsPreserveRecoveryState | PASS | 9.228 |
| multiSkuOrderCompletesAndMergesRepeatedSkus | PASS | 7.236 |
| failedMultiSkuReservationRollsBackAllStockAndCancels(String)[1] | PASS | 2.315 |
| failedMultiSkuReservationRollsBackAllStockAndCancels(String)[2] | PASS | 1.732 |
| duplicateCommandsAndResultsHaveOneBusinessEffect | PASS | 4.728 |
| releaseBeforeReserveAndLateResultsCannotReopenCancelledOrder | PASS | 129.595 |
| releaseFirstRestoresReservedStockOnlyOnce | PASS | 112.710 |
| confirmationFirstWinsRacingCancellation | PASS | 112.561 |
| concurrentOrdersCannotOversellLastItem | PASS | 1.408 |
| inventoryOutboxFailureKeepsAtomicResultAndRecoversAfterRestart | PASS | 84.062 |
| brokerOutageRetainsOrderAndPublishesAutomaticallyAfterRecovery | PASS | 22.361 |
| databaseFailureRollsBackStockInboxAndOutboxThenReplaysFromDlt | PASS | 6.367 |
| poisonMessagesReachBothDltsWithoutBlockingValidOrders | PASS | 8.774 |
| reservationTimeoutAndExhaustedCompensationSupportManualRecovery | PASS | 34.210 |
| crashAfterOutboxClaimRecoversAndFailedCommandsCanBeRetried | PASS | 142.858 |
| postgresSkipLockedAllowsTwoWorkersToClaimDifferentEvents | PASS | 65.108 |
| invalidOrdersReturnBadRequestWithoutPersistingBusinessState | PASS | 0.710 |
| legacyOrderPayloadPublishesThroughOutbox | PASS | 3.040 |
| legacyInventoryResultIsAccepted(String)[1] | PASS | 0.827 |
| legacyInventoryResultIsAccepted(String)[2] | PASS | 0.879 |

### Day10 observability

**9/9 passed**, failures: 0, errors: 0, skipped: 0.
Elapsed: **487.310 seconds**, including process startup/restarts.

| Scenario | Result | Seconds |
|---|---|---:|
| probesAndPrometheusEndpointsExposeOnlyIntendedData | PASS | 3.135 |
| gatewayOrderKafkaResultsShareOneTraceAndCommittedMetrics | PASS | 10.560 |
| brokerOutageKeepsReadinessAndTraceWhileMetricsShowFailure | PASS | 46.067 |
| persistedTraceSurvivesOrderProcessCrash | PASS | 116.638 |
| poisonMessagePublishesDltMetricAndRealPrometheusAlert | PASS | 10.886 |
| failedOutboxAndManualSagaGaugesReachAlertmanagerAndRecover | PASS | 154.857 |
| databaseOutageFailsReadinessButPreservesLivenessAndMetricScrapes | PASS | 15.321 |
| rolledBackSagaTransitionAndDuplicateResultDoNotInflateCounters | PASS | 8.359 |
| grafanaProvisionsDashboardAndBothDatasources | PASS | 0.200 |

Combined result: **29/29 passed**, failures: 0, errors: 0, skipped: 0.

## Runtime regression found and fixed

Enabling KafkaTemplate Observation initially added a synchronous KafkaAdmin.clusterId lookup before send.
The existing unreachable-producer test no longer exhausted three attempts within its original 35 seconds.
A dedicated JVM thread snapshot proved the publisher was waiting in KafkaAdmin.clusterId through
KafkaTemplate.observeSend. Producer templates now omit optional cluster-ID enrichment; Observation,
W3C header propagation and original send/retry timeouts remain enabled. The unchanged regression is included in the final results above.
IntelliJ debugger router tools were unavailable; diagnosis used real service logs and jcmd Thread.print.
Evidence: integration-tests/target/day10-diagnostic-reports/publisher-thread.txt.

## Environment and controls

- Java 21, Spring Boot 4.1.1, PostgreSQL 16, Kafka 4.2.1; packaged service JVMs and real HTTP/JDBC/Kafka.
- Monitoring images: Prometheus 3.2.1, Grafana 11.6.0, Zipkin 3.5.1, Alertmanager 0.28.1, Blackbox 0.26.0.
- Dedicated business project orderflow-it uses PostgreSQL 15432 and Kafka 19092; services use 18081/18082.
  Day10 Gateway uses 19000 with direct test routes. Eureka registration/load balancing is not exercised.
- Dedicated monitoring project orderflow-day10 uses Prometheus 19090, Grafana 13000, Zipkin 19411,
  Alertmanager 19093 and Blackbox 19115. Development databases and containers are not reset.
- Broker/database outages stop and restart dedicated containers; crash verification kills the dedicated order JVM.
  FAILED/MANUAL_RECOVERY are injected only into disposable test rows; alerts reach real Alertmanager and resolve after retry.
- Transaction rollback is injected with an order-database trigger; replay and duplicate delivery verify committed metrics.
- Final build and test execution are sequential: Windows locks running packaged archives.

## Limits and operating notes

- Gauges reflect durable database state across restarts. Counters and timer histograms are process-local and reset on restart;
  dashboards use rate/increase where appropriate. DLT metrics count acknowledged application publications, not total retained records.
- Existing Outbox created_at columns store local timestamps without a zone. Backlog age uses the configured publisher timezone
  (defaults to the JVM zone); instances sharing a database must use one timezone. Legacy rows have no traceparent and start new traces.
- Scheduled timeout recovery starts a new diagnostic trace; correlate it with the original flow by orderId.
- Local tracing samples 100%; Zipkin uses in-memory storage. Sampling and durable trace retention require deployment configuration.
- Alertmanager's local receiver keeps alerts visible in its UI. External email/chat delivery is not configured or exercised.
- Multi-broker failover, load testing, payment/shipping and production deployment are outside this validation.

## Artifacts and reproduction

- Run: `powershell -File scripts/run-day10-tests.ps1`.
- Optional: `-MavenRepository C:/Users/marj-/.m2/repository` and `-KeepInfrastructure`.
- Reports/check evidence: `integration-tests/target/day10-reports/`.
- Service logs: `integration-tests/target/service-logs/run-*/`.
- Regenerate this summary: `python scripts/summarize-day10.py`.
- Runbook: [Day10 observability](day10-observability.md).
- Day9's original summary remains [available](day9-integration-test-summary.md).
