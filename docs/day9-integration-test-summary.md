# Day 9 integration test summary

Generated: 2026-10-13T19:04:41-04:00
Suite timestamp: not recorded

Result: **20/20 passed**, failures: 0, errors: 0, skipped: 0.
Suite elapsed time: **835.422 seconds** (includes service startup and restart).

Environment: Java 21, Spring Boot 4.1.1, PostgreSQL 16, Kafka 4.2.1.
Two real packaged service processes; real HTTP, JDBC, Kafka and Flyway. No business mocks.
Dedicated Docker Compose project orderflow-it: PostgreSQL 15432, Kafka 19092, services 18081/18082.
Development containers and databases were not used for these tests.

| Scenario | Result | Seconds |
|---|---|---:|
| freshAndLegacyFlywayMigrationsPreserveRecoveryState | PASS | 4.249 |
| multiSkuOrderCompletesAndMergesRepeatedSkus | PASS | 5.323 |
| failedMultiSkuReservationRollsBackAllStockAndCancels(String)[1] | PASS | 1.589 |
| failedMultiSkuReservationRollsBackAllStockAndCancels(String)[2] | PASS | 1.261 |
| duplicateCommandsAndResultsHaveOneBusinessEffect | PASS | 2.926 |
| releaseBeforeReserveAndLateResultsCannotReopenCancelledOrder | PASS | 85.144 |
| releaseFirstRestoresReservedStockOnlyOnce | PASS | 85.285 |
| confirmationFirstWinsRacingCancellation | PASS | 127.323 |
| concurrentOrdersCannotOversellLastItem | PASS | 1.571 |
| inventoryOutboxFailureKeepsAtomicResultAndRecoversAfterRestart | PASS | 105.805 |
| brokerOutageRetainsOrderAndPublishesAutomaticallyAfterRecovery | PASS | 20.992 |
| databaseFailureRollsBackStockInboxAndOutboxThenReplaysFromDlt | PASS | 6.394 |
| poisonMessagesReachBothDltsWithoutBlockingValidOrders | PASS | 9.088 |
| reservationTimeoutAndExhaustedCompensationSupportManualRecovery | PASS | 46.946 |
| crashAfterOutboxClaimRecoversAndFailedCommandsCanBeRetried | PASS | 163.503 |
| postgresSkipLockedAllowsTwoWorkersToClaimDifferentEvents | PASS | 79.174 |
| invalidOrdersReturnBadRequestWithoutPersistingBusinessState | PASS | 1.666 |
| legacyOrderPayloadPublishesThroughOutbox | PASS | 2.599 |
| legacyInventoryResultIsAccepted(String)[1] | PASS | 0.953 |
| legacyInventoryResultIsAccepted(String)[2] | PASS | 0.873 |

## Runtime defect found and fixed

The first startup attempt failed because Spring Boot's automatic KafkaTemplate
required ProducerFactory<Object,Object>, while the custom factory was typed
ProducerFactory<String,Object>. Both services now explicitly declare the matching
KafkaTemplate. The final test run exercises startup and real publishing.

Legacy OrderCreated payloads also contain status=PENDING. Type inference now
checks items before interpreting a legacy status as an inventory result;
three additional regression cases cover old order and inventory payloads.

## Scope and controls

- Covers fresh and legacy migrations, multi-SKU completion, duplicate SKU merging,
  insufficient/missing inventory rollback, duplicate events, reordered cancellation,
  confirmation/cancellation races, concurrent stock contention, result Outbox failure,
  broker outage, transaction failure and DLT replay, invalid JSON in both directions,
  timeout/manual recovery, process crash/stale claims, failed command retry,
  PostgreSQL SKIP LOCKED, and request validation.
- Uses five Outbox attempts (three for intentional result-publishing exhaustion)
  and two Saga recovery retries. Polling is 200ms and claim expiry is 3 seconds.
  Normal Saga deadlines are 600 seconds so service startup does not trigger unrelated
  compensation. Consumer session timeout is 6 seconds; service readiness allows 300 seconds.
- Timeout/deadline, exhausted compensation, and abandoned claims are injected by
  updating only dedicated test database rows. No multi-minute clock wait is required.
- Multi-SKU transaction failure is injected by a PostgreSQL trigger on result Outbox
  insertion. Broker outage is a real stop/start of the dedicated Kafka container.
- Crash tests forcibly terminate dedicated JVMs; recovery uses the same database.
- Gateway/Eureka, external payment/shipping, multi-broker failover, and load testing
  are outside this suite. Existing service tests tied to development databases
  are not included in these counts.

## Artifacts and reproduction

- XML: integration-tests/target/day10-reports/TEST-com.rom.orderflow.integration.SagaWorkflowIntegrationTest.xml
- Text report: integration-tests/target/day10-reports/com.rom.orderflow.integration.SagaWorkflowIntegrationTest.txt
- Service logs: integration-tests/target/service-logs/
- Run: powershell -File scripts/run-integration-tests.ps1
- If needed, pass -MavenRepository C:/Users/marj-/.m2/repository.
- Summarize: python scripts/summarize-integration-tests.py
