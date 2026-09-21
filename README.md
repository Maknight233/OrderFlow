# OrderFlow

OrderFlow is a distributed order processing platform built with Java 21, Spring Boot, Spring Cloud, PostgreSQL, Kafka, Redis, Flyway, and Docker.

The project is designed to demonstrate production-oriented microservice patterns such as service discovery, API gateway routing, database-per-service, event-driven communication, database migrations, optimistic locking, inventory reservation, concurrency control, and distributed workflow patterns.

---

## Current Architecture

```text
                              ┌─────────────────────┐
                              │   Discovery Service │
                              │     Eureka :8761    │
                              └──────────┬──────────┘
                                         │
                              Service Registration
                                         │
                 ┌───────────────────────┼───────────────────────┐
                 │                       │                       │
        ┌────────▼────────┐     ┌────────▼────────┐     ┌────────▼─────────┐
        │ Gateway Service │     │  Order Service  │     │ Inventory Service│
        │      :9000      │     │      :8081      │     │      :8082       │
        └────────┬────────┘     └────────┬────────┘     └────────┬─────────┘
                 │                       │                       │
                 │                       │                       │
                 │                ┌──────▼──────┐         ┌──────▼──────┐
                 │                │ PostgreSQL  │         │ PostgreSQL  │
                 │                │  orderflow  │         │inventory_db │
                 │                └─────────────┘         └─────────────┘
                 │
                 │
                 └──── Client traffic enters through Gateway

                           ┌────────────────────┐
                           │       Kafka        │
                           │      :9092         │
                           └────────────────────┘

                           ┌────────────────────┐
                           │       Redis        │
                           │      :6379         │
                           └────────────────────┘
```

---

## Services

### Discovery Service

Provides service discovery using Netflix Eureka.

```text
Port: 8761
```

Responsibilities:

- Service registration
- Service discovery
- Dynamic service lookup

Eureka dashboard:

```text
http://localhost:8761
```

---

### Gateway Service

Acts as the single entry point for external API requests.

```text
Port: 9000
```

Current routes:

```text
/api/orders/**
    → order-service

/api/inventory/**
    → inventory-service
```

Services are resolved through Eureka using Spring Cloud LoadBalancer instead of hard-coded service URLs.

Example:

```text
Client
  ↓
localhost:9000/api/inventory/...
  ↓
Gateway
  ↓
Eureka
  ↓
inventory-service
```

---

### Order Service

Handles order creation and order persistence.

```text
Port: 8081
Database: orderflow
```

Current capabilities:

- Create orders
- Persist orders with PostgreSQL
- Flyway database migrations
- UUID-based primary keys
- Optimistic locking
- Kafka order event publishing

Order table:

```text
orders
├── id
├── customer_id
├── status
├── total_amount
├── currency
├── version
├── created_at
└── updated_at
```

Example order schema:

```sql
CREATE TABLE orders
(
    id           UUID PRIMARY KEY,
    customer_id  UUID           NOT NULL,
    status       VARCHAR(32)    NOT NULL,
    total_amount NUMERIC(19, 2) NOT NULL,
    currency     VARCHAR(3)     NOT NULL DEFAULT 'USD',
    version      BIGINT         NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_orders_total_amount
        CHECK (total_amount >= 0)
);
```

---

### Inventory Service

Handles product inventory and inventory reservations.

```text
Port: 8082
Database: inventory_db
```

Current capabilities:

- Create inventory
- Query inventory by SKU
- Update inventory quantity
- Reserve inventory
- Confirm reservations
- Release reservations
- Optimistic locking
- Concurrency protection
- Reservation idempotency foundation
- Centralized exception handling
- Flyway migrations
- Eureka registration
- Gateway routing

---

## Inventory Domain

Each inventory record is identified by a SKU.

Example:

```text
SKU:
IPHONE17PRO-256-BLK

Product:
iPhone 17 Pro 256GB Black
```

Inventory tracks both available and reserved stock:

```text
quantity
reserved_quantity
```

Example:

```text
Initial stock

quantity          = 10
reserved_quantity = 0
```

After reserving 2 items:

```text
quantity          = 8
reserved_quantity = 2
```

---

## Inventory Reservation Lifecycle

Inventory reservations use three states:

```text
RESERVED
CONFIRMED
RELEASED
```

### Reserve

An order temporarily reserves inventory.

```text
Before

quantity          = 10
reserved_quantity = 0

Reserve 2

After

quantity          = 8
reserved_quantity = 2
status            = RESERVED
```

### Confirm

When the order successfully completes, reserved inventory is permanently consumed.

```text
Before

quantity          = 8
reserved_quantity = 2
status            = RESERVED

After

quantity          = 8
reserved_quantity = 0
status            = CONFIRMED
```

### Release

If the order fails, reserved stock is returned to available inventory.

```text
Before

quantity          = 8
reserved_quantity = 2
status            = RESERVED

After

quantity          = 10
reserved_quantity = 0
status            = RELEASED
```

---

## Inventory API

All APIs can be accessed directly through Inventory Service during development:

```text
http://localhost:8082
```

or through the API Gateway:

```text
http://localhost:9000
```

### Create Inventory

```http
POST /api/inventory
```

Example request:

```json
{
  "sku": "IPHONE17PRO-256-BLK",
  "productName": "iPhone 17 Pro 256GB Black",
  "quantity": 10
}
```

---

### Get Inventory

```http
GET /api/inventory/{sku}
```

Example:

```http
GET /api/inventory/IPHONE17PRO-256-BLK
```

---

### Update Inventory

```http
PUT /api/inventory/{sku}
```

Example request:

```json
{
  "quantity": 20
}
```

---

### Reserve Inventory

```http
POST /api/inventory/reserve
```

Example:

```json
{
  "orderId": "3b3d7971-6be7-4562-8ab7-6860aa690407",
  "sku": "IPHONE17PRO-256-BLK",
  "quantity": 2
}
```

Example response:

```json
{
  "reservationId": "7eb3ad48-5412-4672-a5a7-e0907092db13",
  "orderId": "3b3d7971-6be7-4562-8ab7-6860aa690407",
  "sku": "IPHONE17PRO-256-BLK",
  "quantity": 2,
  "status": "RESERVED"
}
```

---

### Confirm Reservation

```http
POST /api/inventory/reservations/{orderId}/{sku}/confirm
```

Example:

```text
POST /api/inventory/reservations/
3b3d7971-6be7-4562-8ab7-6860aa690407/
IPHONE17PRO-256-BLK/
confirm
```

Result:

```text
RESERVED → CONFIRMED
```

---

### Release Reservation

```http
POST /api/inventory/reservations/{orderId}/{sku}/release
```

Result:

```text
RESERVED → RELEASED
```

Released inventory is returned to the available quantity.

---

## Database Strategy

OrderFlow follows a database-per-service design.

```text
Order Service
     ↓
orderflow database


Inventory Service
     ↓
inventory_db database
```

Services do not directly query another service's database.

Communication between services will occur through APIs and Kafka events.

---

## Database Migrations

Database schemas are managed using Flyway.

Inventory Service currently contains:

```text
db/migration/

V1__create_inventory_table.sql
V2__add_inventory_reservation.sql
```

Flyway migration files are immutable after they have been applied.

Future schema changes should use new migration files:

```text
V1
↓
V2
↓
V3
↓
V4
```

instead of modifying previous migrations.

---

## Optimistic Locking

Inventory uses JPA optimistic locking:

```java
@Version
private Long version;
```

The version column prevents concurrent requests from silently overwriting each other's inventory updates.

Conceptually, Hibernate performs an update similar to:

```sql
UPDATE inventory
SET quantity = ?,
    reserved_quantity = ?,
    version = ?
WHERE id = ?
  AND version = ?;
```

If another transaction has already modified the inventory record, the version no longer matches and the second update fails.

---

## Concurrency Protection

A concurrency integration test verifies that two orders cannot reserve the last available item simultaneously.

Test scenario:

```text
Initial inventory

quantity          = 1
reserved_quantity = 0
```

Two separate orders attempt to reserve the item concurrently:

```text
Order A ──┐
          ├── reserve 1 item
Order B ──┘
```

Both transactions may initially read:

```text
quantity = 1
version  = 0
```

Only one transaction can successfully update the row.

Expected final state:

```text
Successful reservations = 1
Failed reservations     = 1

quantity                 = 0
reserved_quantity        = 1
reservation rows         = 1
```

The losing transaction receives an optimistic locking conflict rather than overwriting the successful transaction.

This protects the inventory system from overselling under concurrent requests.

---

## Transaction Management

Inventory reservation operations run inside database transactions.

Example:

```text
Reserve inventory
       │
       ├── decrease available quantity
       │
       ├── increase reserved quantity
       │
       └── create reservation
```

All operations succeed together or roll back together.

This prevents inconsistent states such as:

```text
inventory decreased
BUT
reservation not created
```

---

## Reservation Idempotency

Reservations are uniquely identified by:

```text
orderId + SKU
```

The database enforces:

```text
UNIQUE(order_id, sku)
```

This prevents the same order from accidentally creating duplicate reservations for the same SKU.

This will become especially important when Kafka consumers are introduced because event delivery may occur more than once.

---

## Exception Handling

Inventory Service uses centralized exception handling with:

```java
@RestControllerAdvice
```

Current error cases include:

```text
404 Not Found
Inventory SKU does not exist

409 Conflict
Duplicate SKU

409 Conflict
Insufficient inventory

409 Conflict
Optimistic locking conflict

400 Bad Request
Request validation failure
```

---

## Technology Stack

### Backend

- Java 21
- Spring Boot 4.1.1
- Spring MVC
- Spring Data JPA
- Spring Validation
- Spring Cloud
- Netflix Eureka
- Spring Cloud Gateway

### Database

- PostgreSQL 16
- Flyway
- Hibernate / JPA

### Messaging

- Apache Kafka

### Cache

- Redis

### Infrastructure

- Docker
- Docker Compose

### Testing

- JUnit
- Spring Boot Test
- AssertJ
- Concurrent integration testing

---

## Docker Infrastructure

Current local infrastructure:

```text
PostgreSQL
Port: 5432

Redis
Port: 6379

Kafka
Port: 9092
```

Start infrastructure:

```bash
docker compose up -d
```

Check containers:

```bash
docker ps
```

Stop infrastructure:

```bash
docker compose down
```

---

## Recommended Local Startup Order

```text
1. Docker infrastructure
   ├── PostgreSQL
   ├── Redis
   └── Kafka

2. Discovery Service

3. Order Service

4. Inventory Service

5. Gateway Service
```

---

## Current Request Flow

Order API:

```text
Client
  ↓
API Gateway
  ↓
Eureka
  ↓
Order Service
  ↓
PostgreSQL
```

Inventory API:

```text
Client
  ↓
API Gateway
  ↓
Eureka
  ↓
Inventory Service
  ↓
PostgreSQL
```

---

## Current Project Structure

```text
OrderFlow
│
├── discovery-service
│
├── gateway-service
│
├── order-service
│
├── inventory-service
│
├── docker-compose.yml
│
├── pom.xml
└── README.md
```

---

## Development Progress

### Day 1 — Infrastructure

Completed:

- Maven multi-module project
- Java 21
- Spring Boot
- Eureka Discovery Server
- PostgreSQL
- Redis
- Kafka
- Docker Compose

### Day 2 — Order Service

Completed:

- Order Service
- PostgreSQL integration
- JPA
- Flyway
- Order entity
- Create Order API
- UUID primary keys
- Database validation

### Day 3 — Gateway and Messaging

Completed:

- API Gateway
- Eureka-based service routing
- Order Service routing
- Kafka infrastructure
- Order event publishing

### Day 4 — Inventory Service

Completed:

- Inventory Service
- Independent inventory database
- Flyway V1 and V2 migrations
- Inventory CRUD
- SKU lookup
- Inventory reservations
- Reserve inventory
- Confirm reservation
- Release reservation
- Optimistic locking
- Transaction management
- Reservation idempotency foundation
- Centralized exception handling
- Eureka registration
- Gateway routing
- Concurrent inventory reservation test
- Overselling protection

---

## Next Step — Day 5

The next stage will connect Order Service and Inventory Service through Kafka.

Target flow:

```text
Client
  ↓
Create Order
  ↓
Order Service
  ↓
OrderCreatedEvent
  ↓
Kafka
  ↓
Inventory Service
  ↓
Reserve Inventory
  ↓
        ┌─────────────────────┐
        │                     │
        ▼                     ▼
InventoryReservedEvent   InventoryRejectedEvent
        │                     │
        ▼                     ▼
   Continue workflow      Compensate / Reject
```

Planned work:

- Kafka consumer in Inventory Service
- Consume `OrderCreatedEvent`
- Trigger inventory reservation
- Publish `InventoryReservedEvent`
- Publish `InventoryRejectedEvent`
- Consumer idempotency
- Retry handling
- Event-driven order workflow

Later stages will introduce:

- Transactional Outbox
- Saga workflow
- Payment Service
- Retry / Dead Letter Queue
- Redis caching
- Observability
- Dockerized services
- Kubernetes deployment
- AWS deployment

---

## Project Goal

OrderFlow is intended to demonstrate how a production-style distributed system handles:

```text
Service discovery
API gateway routing
Database ownership
Database migrations
Distributed communication
Kafka events
Inventory reservation
Concurrent updates
Optimistic locking
Idempotency
Transactional consistency
Saga workflows
Failure recovery
Observability
Cloud deployment
```

The goal is not only to build CRUD APIs, but to demonstrate the engineering challenges involved in reliable distributed systems.