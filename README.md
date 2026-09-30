# OrderFlow

OrderFlow is a distributed order processing platform built with Java 21, Spring Boot, Spring Cloud, PostgreSQL, Kafka, Redis, and Docker.

The project is designed to demonstrate production-oriented microservice patterns such as service discovery, API gateway routing, event-driven communication, database migrations, optimistic locking, Kafka retry / dead-letter handling, Saga-style compensation, Transactional Outbox, consumer idempotency, and asynchronous order workflows.

## Current Status

OrderFlow currently supports an end-to-end Kafka-driven order and inventory workflow with:

- asynchronous order processing
- Kafka retry and dead-letter handling
- Saga-style compensation for partial reservation failures
- Transactional Outbox for reliable `OrderCreatedEvent` publication
- consumer-side duplicate-event protection
- optimistic locking for inventory concurrency

```text
Client
  |
  v
API Gateway :9000
  |
  v
Order Service :8081
  |
  | @Transactional
  |
  +---- save Order(PENDING)
  |
  +---- save OutboxEvent(PENDING)
  |
  v
PostgreSQL
  |
  v
Outbox Publisher
  |
  | poll pending events
  | publish and wait for Kafka ACK
  |
  v
Kafka: order-events
  |
  v
Inventory Service :8082
  |
  | check processed_events
  |
  +---- duplicate event ----> ignore
  |
  v
reserve inventory
  |
  +--------------------+--------------------+
  |                                         |
  v                                         v
RESERVED                                REJECTED
  |                                         |
  +---------- InventoryResultEvent ---------+
                    |
                    v
           Kafka: inventory-events
                    |
                    v
             Order Service
                    |
          +---------+---------+
          |                   |
          v                   v
     CONFIRMED            CANCELLED
```

The initial `POST /api/orders` response returns the newly created order in `PENDING` status. Inventory processing happens asynchronously through Kafka. A later order lookup reflects the final `CONFIRMED` or `CANCELLED` state.

## Architecture

```text
                         +----------------------+
                         |    Eureka Server     |
                         |        :8761         |
                         +----------+-----------+
                                    |
                          Service Registration
                                    |
              +---------------------+----------------------+
              |                                            |
              v                                            v
     +------------------+                        +-------------------+
     | Gateway Service  |                        |  Order Service    |
     |      :9000       |----------------------->|      :8081        |
     +--------+---------+                        +---------+---------+
              |                                            |
              |                                    DB Transaction
              |                                            |
              |                              +-------------+-------------+
              |                              |                           |
              |                              v                           v
              |                           orders                  outbox_events
              |                                                         |
              |                                                         v
              |                                                Outbox Publisher
              |                                                         |
              |                                                  OrderCreatedEvent
              |                                                         |
              |                                                         v
              |                                                  +---------------+
              |                                                  |     Kafka     |
              |                                                  | order-events  |
              |                                                  +-------+-------+
              |                                                          |
              |                                                          v
              |                                                +-------------------+
              +----------------------------------------------->| Inventory Service |
                                                               |       :8082       |
                                                               +---------+---------+
                                                                         |
                                                             InventoryResultEvent
                                                                         |
                                                                         v
                                                                 +---------------+
                                                                 |     Kafka     |
                                                                 |inventory-events|
                                                                 +-------+-------+
                                                                         |
                                                                         v
                                                                 Order Service

Infrastructure:
- PostgreSQL
- Redis
- Apache Kafka
- Docker Compose
```

## Services

### discovery-service

Netflix Eureka service registry.

- Port: `8761`
- Registers and discovers OrderFlow microservices
- Removes the need for hardcoded service locations

### gateway-service

Spring Cloud Gateway entry point.

- Port: `9000`
- Routes external API requests to registered services
- Uses Eureka-based service discovery

Current request flow:

```text
Client
  -> Gateway
  -> Eureka service discovery
  -> target microservice
```

### order-service

Order lifecycle service.

- Port: `8081`
- Persists orders in PostgreSQL
- New orders begin in `PENDING`
- Stores `OrderCreatedEvent` payloads in `outbox_events`
- Writes the order and outbox record in the same database transaction
- Uses a scheduled Outbox Publisher to poll `PENDING` events
- Publishes `OrderCreatedEvent` to Kafka only through the outbox relay
- Waits for Kafka send completion before marking the outbox event `PUBLISHED`
- Keeps failed events `PENDING` so they can be retried automatically
- Uses `orderId` as the Kafka message key
- Consumes `InventoryResultEvent`
- Updates order state asynchronously

Current state transitions:

```text
PENDING
  |
  +-- inventory RESERVED --> CONFIRMED
  |
  +-- inventory REJECTED --> CANCELLED

CONFIRMED
  |
  +-- future payment / fulfillment flow --> COMPLETED
```

### inventory-service

Inventory and reservation service.

- Port: `8082`
- PostgreSQL persistence
- Inventory CRUD
- Inventory reservation workflow
- Idempotent reservation using `orderId + sku`
- Idempotent release / compensation handling
- Consumer idempotency using `processed_events`
- Duplicate `OrderCreatedEvent` messages are ignored by `eventId`
- Optimistic locking with JPA `@Version`
- Handles concurrent reservation attempts
- Kafka consumer retry with fixed backoff
- Dead-letter publishing to `order-events-dlt`
- `ErrorHandlingDeserializer` for deserialization failures
- Saga-style compensation for partial multi-item reservation failures
- Publishes inventory processing results to Kafka

Reservation behavior:

```text
available inventory
      |
      v
reserve(orderId, sku, quantity)
      |
      +-- success
      |     quantity -= requested
      |     reservedQuantity += requested
      |     reservation status = RESERVED
      |
      +-- inventory missing / insufficient
            publish REJECTED result
```

## Transactional Outbox

`order-service` no longer performs the database write and Kafka publish as two independent operations.

The order and its outgoing event are persisted atomically:

```text
@Transactional
      |
      +-- INSERT orders
      |
      +-- INSERT outbox_events (PENDING)
      |
      v
    COMMIT
```

The background publisher then relays pending events to Kafka:

```text
outbox_events
     |
     | status = PENDING
     v
Outbox Publisher
     |
     v
KafkaTemplate.send(...)
     |
     v
wait for ACK
     |
     +-- failure --> keep PENDING
     |
     +-- success --> status = PUBLISHED
                    published_at = timestamp
```

This prevents an order from being committed while its corresponding `OrderCreatedEvent` is permanently lost because Kafka was temporarily unavailable.

A broker-outage recovery test was verified:

```text
Kafka DOWN
   |
Create Order
   |
   +-- order saved
   +-- outbox event saved as PENDING
   |
Kafka UP
   |
Outbox Publisher retries
   |
event published
   |
outbox status -> PUBLISHED
```

## Consumer Idempotency

The Transactional Outbox provides at-least-once style delivery, so a message can still be delivered more than once in failure scenarios.

`inventory-service` maintains a `processed_events` table keyed by `eventId`.

```text
OrderCreatedEvent
      |
      v
processed_events contains eventId?
      |
  +---+---+
  |       |
 YES      NO
  |       |
ignore    process inventory
          |
          v
      record eventId
```

Example table:

```text
processed_events
-------------------------------
event_id       UUID PRIMARY KEY
event_type     VARCHAR
processed_at   TIMESTAMP
```

Duplicate-delivery behavior was verified by re-publishing the same outbox event. The second delivery was ignored and inventory quantities remained unchanged.

## Kafka Event Flow

### Topic: `order-events`

Produced by `order-service` through the Outbox Publisher.

Example:

```json
{
  "eventId": "842523a6-8818-456c-9dad-5c42b6f62bc5",
  "orderId": "53bcb071-f72e-4e26-ac11-5291e7c3ed96",
  "customerId": "11111111-1111-1111-1111-111111111113",
  "status": "PENDING",
  "totalAmount": 1299.99,
  "currency": "USD",
  "items": [
    {
      "sku": "IPHONE17PRO-256-BLK",
      "quantity": 1
    }
  ],
  "occurredAt": "2026-09-23T17:35:44Z"
}
```

Kafka key:

```text
orderId
```

Consumed by `inventory-service`.

### Topic: `inventory-events`

Produced by `inventory-service`.

Unified event:

```json
{
  "eventId": "4f098f33-d095-46ca-8ba3-d3637cc18df0",
  "orderId": "53bcb071-f72e-4e26-ac11-5291e7c3ed96",
  "status": "RESERVED",
  "reason": null,
  "occurredAt": "2026-09-23T17:35:45Z"
}
```

Rejected example:

```json
{
  "eventId": "5b642854-55d7-49ab-a150-a91513ea331b",
  "orderId": "53bcb071-f72e-4e26-ac11-5291e7c3ed96",
  "status": "REJECTED",
  "reason": "Insufficient inventory",
  "occurredAt": "2026-09-23T17:35:45Z"
}
```

Consumed by `order-service`.

Result mapping:

```text
RESERVED -> CONFIRMED
REJECTED -> CANCELLED
```

### Topic: `order-events-dlt`

Failed `order-events` records that exhaust the configured retry policy are published to `order-events-dlt`.

Current retry policy:

```text
Initial delivery
    |
    v
Retry #1 after 2 seconds
    |
    v
Retry #2 after 2 seconds
    |
    v
order-events-dlt
```

This gives a total of three delivery attempts for retryable technical failures.

Business failures such as insufficient inventory are handled by the inventory workflow and do not use the technical retry path.

## Example End-to-End Request

Create an order through the Gateway:

```http
POST http://localhost:9000/api/orders
Content-Type: application/json
```

```json
{
  "customerId": "11111111-1111-1111-1111-111111111113",
  "totalAmount": 1299.99,
  "currency": "USD",
  "items": [
    {
      "sku": "IPHONE17PRO-256-BLK",
      "quantity": 1
    }
  ]
}
```

Initial response:

```json
{
  "id": "f8cecd57-7f35-4586-af53-2464227b75f1",
  "customerId": "11111111-1111-1111-1111-111111111113",
  "status": "PENDING",
  "totalAmount": 1299.99,
  "currency": "USD",
  "version": 0
}
```

Because inventory processing is asynchronous, `PENDING` is expected in the create response.

After Kafka processing:

```text
Inventory available:
PENDING -> CONFIRMED

Inventory unavailable:
PENDING -> CANCELLED
```

## Inventory Concurrency and Idempotency

Inventory reservation uses optimistic locking to protect stock updates under concurrent requests.

```java
@Version
private Long version;
```

If multiple transactions attempt to reserve the last available unit, only a valid update can commit against the expected entity version.

Inventory reservations also protect against duplicate processing using:

```text
(orderId, sku)
```

If the same order is processed again, the existing reservation prevents inventory from being deducted twice.

Inventory release is also idempotent. If a reservation is already in `RELEASED` state, calling `release(orderId, sku)` again returns the existing reservation without modifying inventory quantities again.

For multi-item orders, successful reservations are tracked while items are processed. If a later item fails with a business exception, previously reserved items are released in reverse order as Saga compensating actions.

```text
Reserve Item A -> SUCCESS
Reserve Item B -> FAILED
        |
        v
Release Item A
        |
        v
Publish REJECTED
        |
        v
Order CANCELLED
```

Both `Inventory` and `InventoryReservation` use JPA `@Version` fields for optimistic locking.

Consumer-level idempotency adds another layer of protection by tracking processed Kafka `eventId` values.

## Persistence

OrderFlow currently uses PostgreSQL for transactional data.

Database migrations are managed with Flyway.

Current persistence responsibilities include:

```text
order-service
  -> orders
  -> outbox_events

inventory-service
  -> inventory
  -> inventory reservations
  -> processed_events
```

Redis is included in the local infrastructure for upcoming caching and distributed workflow features.

## Technology Stack

| Area | Technology |
|---|---|
| Language | Java 21 |
| Backend | Spring Boot 4.1.1 |
| Service Discovery | Netflix Eureka |
| API Gateway | Spring Cloud Gateway |
| Messaging | Apache Kafka |
| Database | PostgreSQL 16 |
| Cache / Infrastructure | Redis 7 |
| Persistence | Spring Data JPA / Hibernate |
| Migrations | Flyway |
| Build | Maven |
| Containers | Docker / Docker Compose |
| Testing | JUnit / Mockito / Postman |
| Concurrency | JPA Optimistic Locking |
| Reliability | Kafka Retry / DLT / Saga Compensation / Transactional Outbox / Consumer Idempotency |
| Architecture | Microservices / Event-Driven Architecture |

## Local Infrastructure

Docker Compose currently provides the main development infrastructure:

```text
PostgreSQL :5432
Redis      :6379
Kafka      :9092
```

Typical startup order:

```text
1. Docker infrastructure
2. discovery-service
3. gateway-service
4. order-service
5. inventory-service
```

Example:

```bash
docker compose up -d
```

Then verify containers:

```bash
docker ps
```

## Current Project Structure

```text
OrderFlow/
├── discovery-service/
├── gateway-service/
├── order-service/
├── inventory-service/
├── docker-compose.yml
└── pom.xml
```

The project is organized as a Maven multi-module application.

## Implemented Milestones

### Day 1 - Foundation

- Java 21 / Spring Boot 4.1.1 project setup
- Maven multi-module structure
- Eureka discovery service
- Docker Compose infrastructure
- PostgreSQL
- Redis
- Kafka

### Day 2 - Order Service

- Order entity and repository
- PostgreSQL persistence
- Flyway migrations
- Create Order API
- Order status model
- API Gateway routing

### Day 3 - Kafka Producer

- `OrderCreatedEvent`
- Kafka JSON serialization
- `OrderEventPublisher`
- `order-events`
- Kafka key = `orderId`
- Event publication after order creation
- Kafka console verification

### Day 4 - Inventory Service

- Inventory CRUD
- PostgreSQL inventory persistence
- Flyway migrations
- Reserve / confirm / release reservation operations
- Inventory reservation entity
- Idempotent reservation handling
- Optimistic locking
- Concurrent reservation testing
- Centralized inventory exceptions

### Day 5 - Event-Driven Order Workflow

- Added order items to `OrderCreatedEvent`
- Inventory Kafka consumer
- Cross-service JSON event contracts
- Inventory reservation triggered asynchronously
- Unified `InventoryResultEvent`
- `inventory-events` topic
- Order Service Kafka consumer
- `PENDING -> CONFIRMED` success flow
- `PENDING -> CANCELLED` rejection flow
- End-to-end success and insufficient-inventory tests

### Day 6 - Kafka Reliability and Saga Compensation

- Added `ErrorHandlingDeserializer` to the inventory Kafka consumer
- Added Spring Kafka `DefaultErrorHandler`
- Added fixed retry backoff: 2 retries with 2-second delay
- Added `order-events-dlt`
- Verified retryable consumer failure -> retry -> DLT flow
- Separated business failures from technical failures
- Added Saga-style compensation for partial multi-item reservations
- Releases previously reserved inventory when a later item fails
- Executes compensation in reverse order
- Added idempotent release protection
- Added optimistic locking to inventory reservations with JPA `@Version`
- Verified repeated `release()` calls do not restore stock twice

### Day 7 - Transactional Outbox and Consumer Idempotency

- Added `outbox_events` table to `order-service`
- Added `OutboxEvent`, `OutboxStatus`, and `OutboxEventRepository`
- Persisted `Order` and `OutboxEvent` in the same database transaction
- Removed direct Kafka publication from the create-order transaction
- Added scheduled polling Outbox Publisher
- Waited for Kafka send completion before marking events `PUBLISHED`
- Kept failed events `PENDING` for automatic retry
- Verified Kafka broker outage -> pending outbox -> recovery -> successful publication
- Added `processed_events` table to `inventory-service`
- Added processed-event tracking by Kafka `eventId`
- Ignored duplicate `OrderCreatedEvent` deliveries
- Verified duplicate delivery does not reserve inventory twice

## Current Event-Driven Workflow

```text
Create Order
    |
    v
+-----------------------+
| DB Transaction        |
|                       |
| orders                |
| outbox_events:PENDING |
+-----------+-----------+
            |
            v
     Outbox Publisher
            |
            v
      Kafka: order-events
            |
            v
     Inventory Service
            |
            v
  processed_events check
            |
     +------+------+
     |             |
 duplicate       new event
     |             |
   ignore           v
                Reserve Items
                     |
        +------------+------------+
        |                         |
        v                         v
     SUCCESS                 BUSINESS FAILURE
        |                         |
        |                 Saga Compensation
        |                         |
        v                         v
    RESERVED                  REJECTED
        |                         |
        +-----------+-------------+
                    |
                    v
          Kafka: inventory-events
                    |
                    v
             Order Service
                    |
          +---------+---------+
          |                   |
          v                   v
     CONFIRMED            CANCELLED

Technical Kafka consumer failure:
order-events
    |
    v
retry
    |
    v
retry
    |
    v
order-events-dlt
```

## Reliability Guarantees Implemented

The current design combines several layers of protection:

```text
Optimistic Locking
    -> protects concurrent inventory updates

Idempotent Reservation
    -> protects repeated reserve requests

Idempotent Release
    -> protects repeated compensation

Kafka Retry + DLT
    -> handles retryable consumer-side technical failures

Saga Compensation
    -> restores earlier reservations after later business failure

Transactional Outbox
    -> prevents committed orders from losing outgoing events

Consumer Idempotency
    -> prevents duplicate Kafka events from applying inventory changes twice
```

## Next Steps

Planned reliability and workflow work includes:

- concurrency-safe outbox publishing for multiple `order-service` instances
- outbox retry metadata such as `retry_count`, `last_error`, and `next_retry_at`
- row claiming / locking such as `FOR UPDATE SKIP LOCKED`
- cleanup / retention strategy for old `PUBLISHED` outbox events
- additional integration and concurrency tests
- payment workflow
- notification workflow
- observability and operational metrics

Later project phases will keep the same event-driven architecture while expanding distributed workflow and reliability patterns.

## Design Goals

OrderFlow is intentionally being built beyond basic CRUD. The project focuses on distributed-system concerns that commonly appear in production systems:

- asynchronous communication
- eventual consistency
- service decoupling
- idempotency
- concurrency control
- event ordering through stable Kafka keys
- failure handling
- compensating transactions
- reliable event publication
- duplicate-event protection
- database migration management
- service discovery
- centralized API routing
