# Day 9: Order and inventory Saga

The order service coordinates a durable two-service Saga. Each service owns its
database transaction; Kafka delivery is at least once. Development steps 1-9
are implemented. Step 10 adds an [isolated end-to-end integration suite](../integration-tests/README.md);
executed results are in the [integration test summary](day9-integration-test-summary.md).

## State and event contract

| Saga state | Order status | Pending command |
|---|---|---|
| WAITING_RESERVATION | PENDING | ORDER_CREATED |
| WAITING_CONFIRMATION | CONFIRMED | INVENTORY_CONFIRM_REQUESTED |
| COMPENSATING | CANCELLING | INVENTORY_RELEASE_REQUESTED |
| COMPLETED | COMPLETED | None |
| CANCELLED | CANCELLED | None |
| MANUAL_RECOVERY | Previous status | Operator recovery |

COMPLETED means inventory has been confirmed, not that payment, shipment, or
delivery has occurred.

Commands use order-events; results use inventory-events. Both use order ID
as the Kafka key. Results are INVENTORY_RESERVED, INVENTORY_REJECTED,
INVENTORY_CONFIRMED, and INVENTORY_RELEASED.

Both services use the same JSON envelope shape, defined locally by WorkflowEvent:
eventId, orderId, eventType, schemaVersion (1), items, status, reason, occurredAt.
The status field and absent schemaVersion/eventType support persisted Day 7/8
JSON. New events use eventType. Unknown types and versions go to the DLT.
Multi-item requests merge repeated SKUs before persisting order_items.

## Transaction boundaries and recovery

Order creation commits the order, its items, Saga, and initial Outbox together.
Inventory reservation commits all SKUs, workflow state, inbox record, and
result Outbox together. A business rejection rolls back that transaction before
a separate transaction persists rejection and result. Technical failures
propagate to Kafka retry/DLT rather than becoming business rejections.

Order result consumption locks the Saga row and commits the transition,
processed_inventory_events record, and next command Outbox together.
Inventory commands lock one inventory_workflows row before checking
processed_events. Inventory and reservation optimistic locking still applies
between different orders competing for stock.

Release commits a durable RELEASED workflow even if no reservation exists.
A delayed reserve cannot recreate stock reservations after cancellation.
Cancellation becomes final only after INVENTORY_RELEASED.
Confirmation is irreversible: if confirmation commits before release, inventory
returns INVENTORY_CONFIRMED and the order completes.
Mixed legacy CONFIRMED/RELEASED reservations require manual data review.

Both Outbox publishers use SKIP LOCKED, exponential backoff, stale claim
recovery, and a unique token per claim. Acknowledgements from expired claims
cannot overwrite newer claims. After five failed attempts, rows stay FAILED
and a scheduled monitor reports their event IDs for operator retry.

Defaults:
- Outbox polling: 2 seconds; batch: 10; claim expiry: 120 seconds.
- Outbox failure delays: 5, 10, 20, 40 seconds; five attempts total.
- Permanent Outbox failure scan: 60 seconds.
- Saga step timeout: 120 seconds; recovery scan: 5 seconds.
- Reservation timeout starts compensation.
- Confirmation timeout retries five times, then starts compensation.
- Compensation timeout retries five times, then enters MANUAL_RECOVERY.
- Kafka transient listener failures: two retries, 2 seconds apart, then DLT.
- Consumer offsets commit per record after the database transaction returns.

Settings live under orderflow.saga, orderflow.outbox, and spring.kafka
in application.yaml. Failed Outbox delivery does not roll back committed
business transactions.

## API

| Method | Path | Behavior |
|---|---|---|
| GET | /api/orders/{orderId}/saga | Read state, reason, retries and deadline |
| POST | /api/orders/{orderId}/cancel | Request cancellation; optional JSON reason |
| POST | /api/orders/{orderId}/saga/retry | Resume MANUAL_RECOVERY with a new command |
| POST | /api/orders/outbox/{eventId}/retry | Requeue FAILED command matching current Saga |
| POST | /api/inventory/outbox/{eventId}/retry | Requeue FAILED inventory result |

Cancellation returns 202; completed orders return 409.
Outbox retry returns 202, missing events return 404, and non-FAILED events return
409. Saga retry returns 409 unless the Saga awaits manual recovery.

Inspect failures before retrying:

```sql
SELECT id, aggregate_id, event_type, retry_count, last_error
FROM outbox_events WHERE status = 'FAILED' ORDER BY created_at;

-- Run in the order database:
SELECT order_id, state, recovery_state, failure_reason, retry_count, deadline
FROM order_sagas WHERE state = 'MANUAL_RECOVERY';
```

A retried Saga command gets a fresh event ID so inventory can return its durable
result without repeating stock changes. Requeueing Outbox preserves the
original event ID. DLT records remain for diagnosis; recovery does not
automatically replay poison messages.

## Migrations and rollout

- Order V4 adds order_items, order_sagas, and processed_inventory_events.
- Inventory V5 adds inventory_workflows and outbox_events.
- Order V4 backfills items from legacy ORDER_CREATED payloads, resumes legacy
  confirmed orders at confirmation, and releases legacy cancelled orders.
  Active legacy orders with no recoverable item payload enter MANUAL_RECOVERY.
- Inventory V5 preserves final legacy reservation states.

Stop both old business-service instances before upgrading both services.
Old consumers do not understand new commands. Flyway runs migrations on
startup. The integration suite verifies fresh and legacy upgrades in dedicated
test databases; it does not apply migrations to development databases.

Compose mounts a PostgreSQL initialization script for inventory_db.
It runs only for a new PostgreSQL data volume. An existing environment that
lacks inventory_db must create it explicitly; do not remove its existing volume.

Existing direct per-SKU inventory APIs remain available. Order workflows
should use Saga commands; manually changing individual reservations can require
operator recovery if their states diverge.

## Development validation

Compile the changed services with Java 21:

```powershell
mvn -pl order-service,inventory-service -am -DskipTests compile
```

Step 10 runs packaged services against real Kafka and PostgreSQL. It covers
workflow completion, rejection and compensation, message idempotency, concurrent
stock contention, broker and process recovery, transactional rollback, DLT,
Outbox retries, and fresh/legacy migrations. See the
[test instructions](../integration-tests/README.md) and
[executed summary](day9-integration-test-summary.md) for coverage and limits.
