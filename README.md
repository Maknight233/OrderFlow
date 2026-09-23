# OrderFlow

OrderFlow is a distributed order processing platform built with Java 21, Spring Boot, Spring Cloud, PostgreSQL, Kafka, Redis, and Docker.

The project is designed to demonstrate production-oriented microservice patterns such as service discovery, API gateway routing, event-driven communication, database migrations, optimistic locking, idempotent inventory reservation, and asynchronous order workflows.

## Current Status

OrderFlow currently supports an end-to-end Kafka-driven order and inventory workflow:

```text
Client
  |
  v
API Gateway :9000
  |
  v
Order Service :8081
  |
  | save order as PENDING
  |
  +---- OrderCreatedEvent ----------------------+
  |                                             |
  |                                   Kafka: order-events
  |                                             |
  |                                             v
  |                                  Inventory Service :8082
  |                                             |
  |                                     reserve inventory
  |                                             |
  |                         +-------------------+-------------------+
  |                         |                                       |
  |                         v                                       v
  |                  RESERVED                                  REJECTED
  |                         |                                       |
  |                         +----------- InventoryResultEvent ------+
  |                                             |
  |                                  Kafka: inventory-events
  |                                             |
  +---------------------------------------------+
                                                |
                                                v
                                        Order Service
                                                |
                              +-----------------+-----------------+
                              |                                   |
                              v                                   v
                         CONFIRMED                            CANCELLED
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
              |                                            |
              |                                  OrderCreatedEvent
              |                                            |
              |                                            v
              |                                    +---------------+
              |                                    |     Kafka     |
              |                                    | order-events  |
              |                                    +-------+-------+
              |                                            |
              |                                            v
              |                                  +-------------------+
              +--------------------------------->| Inventory Service |
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
- Publishes `OrderCreatedEvent`
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
- Optimistic locking with JPA `@Version`
- Handles concurrent reservation attempts
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

## Kafka Event Flow

### Topic: `order-events`

Produced by `order-service`.

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

Inventory reservations also protect against duplicate processing:

```text
(orderId, sku)
```

If the same order event is delivered more than once with the same quantity, the existing reservation is returned rather than deducting inventory twice.

This is important because Kafka consumers should be designed for at-least-once delivery semantics.

## Persistence

OrderFlow currently uses PostgreSQL for transactional data.

Database migrations are managed with Flyway.

Current persistence responsibilities include:

```text
order-service
  -> orders

inventory-service
  -> inventory
  -> inventory reservations
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

## Current Event-Driven Workflow

```text
Create Order
    |
    v
PENDING
    |
    v
OrderCreatedEvent
    |
    v
Kafka
    |
    v
Reserve Inventory
    |
    +---------------------------+
    |                           |
    v                           v
RESERVED                    REJECTED
    |                           |
    v                           v
InventoryResultEvent      InventoryResultEvent
    |                           |
    +-------------+-------------+
                  |
                  v
                Kafka
                  |
                  v
            Order Service
                  |
        +---------+---------+
        |                   |
        v                   v
   CONFIRMED            CANCELLED
```

## Next Steps

Planned reliability work includes:

- Kafka `ErrorHandlingDeserializer`
- Retry and backoff policies
- Dead-letter topics
- Saga-style compensation for multi-item reservation failures
- Release previously reserved inventory when a later item fails
- Transactional Outbox pattern
- Additional integration and concurrency tests

Later project phases will add payment and notification workflows while keeping the same event-driven architecture.

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
- database migration management
- service discovery
- centralized API routing

