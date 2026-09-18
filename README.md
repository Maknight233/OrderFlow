# OrderFlow

OrderFlow is a distributed order processing platform built with Java 21, Spring Boot, Spring Cloud, PostgreSQL, Kafka, Redis, and Docker.

The project is designed to demonstrate production-oriented microservice architecture and distributed-system patterns, including service discovery, API gateway routing, event-driven communication, database migrations, optimistic locking, caching, transactional outbox, idempotency, and distributed workflows.

---

## Tech Stack

### Backend

- Java 21
- Spring Boot
- Spring Cloud
- Spring Cloud Gateway
- Netflix Eureka
- Spring Data JPA
- Bean Validation

### Data & Messaging

- PostgreSQL
- Flyway
- Apache Kafka
- Redis

### Testing

- JUnit 5
- Mockito

### Infrastructure

- Docker
- Docker Compose
- Maven

---

## Services

| Service | Port | Responsibility |
|---|---:|---|
| `discovery-service` | 8761 | Eureka service registry and service discovery |
| `gateway-service` | 9000 | API Gateway and load-balanced service routing |
| `order-service` | 8081 | Order creation, retrieval, persistence, and event publishing |

---

## Current Architecture

```text
                              ┌───────────────────────┐
                              │    Eureka Server      │
                              │       :8761           │
                              └───────────┬───────────┘
                                          │
                               Registration / Discovery
                                          │
                      ┌───────────────────┴───────────────────┐
                      │                                       │
              ┌───────▼────────┐                      ┌───────▼────────┐
              │ Gateway Service│                      │  Order Service │
              │     :9000      │                      │     :8081      │
              └───────┬────────┘                      └───────┬────────┘
                      │                                       │
                      │ lb://order-service                    │
                      └───────────────────────────────────────►│
                                                              │
                                      ┌───────────────────────┴───────────────────────┐
                                      │                                               │
                              ┌───────▼────────┐                              ┌───────▼────────┐
                              │   PostgreSQL   │                              │     Kafka      │
                              │     :5432      │                              │     :9092      │
                              └────────────────┘                              └───────┬────────┘
                                                                                     │
                                                                                     ▼
                                                                            ┌────────────────┐
                                                                            │  order-events  │
                                                                            └────────────────┘


                              Supporting Infrastructure

                              ┌───────────────────────┐
                              │        Redis          │
                              │        :6379          │
                              └───────────────────────┘
```

---

## Request Flow

External clients communicate with OrderFlow through the API Gateway.

```text
Client / Postman
       │
       ▼
Gateway Service :9000
       │
       │ Eureka service discovery
       │ lb://order-service
       ▼
Order Service :8081
       │
       ├──────────────► PostgreSQL
       │
       └──────────────► Kafka
                           │
                           ▼
                      order-events
```

---

## Implemented Features

### Service Discovery

- Eureka Discovery Server
- `gateway-service` registered with Eureka
- `order-service` registered with Eureka
- Service lookup by application name

### API Gateway

- Spring Cloud Gateway
- External API entry point on port `9000`
- Load-balanced routing through Eureka
- Order routes configured with:

```text
lb://order-service
```

Current route:

```text
/api/orders/**
```

### Order Management

- Create orders
- Retrieve orders by ID
- Initial order status set to `PENDING`
- UUID-based order IDs
- PostgreSQL persistence
- JPA entity mapping
- Optimistic locking using `@Version`
- Automatic creation and update timestamps

### Database Management

- PostgreSQL 16
- Flyway migrations
- Version-controlled database schema
- `orders` table created through migration scripts

### Validation & Error Handling

- Bean Validation
- Global exception handling
- Invalid request handling
- `404 Not Found` handling for missing orders

### Kafka Event Publishing

When an order is successfully created, `order-service` publishes an `OrderCreatedEvent` to Kafka.

Kafka topic:

```text
order-events
```

Kafka message key:

```text
orderId
```

Using `orderId` as the Kafka key ensures that events belonging to the same order are consistently assigned to the same Kafka partition, preserving event ordering within that partition.

Example event:

```json
{
  "eventId": "8fd234d8-dbb9-4d79-bfe4-3b3b99a63bc1",
  "orderId": "225f90f3-475c-4a3e-92a2-81d391bd2c0d",
  "customerId": "11111111-1111-1111-1111-111111111114",
  "status": "PENDING",
  "totalAmount": 99.99,
  "currency": "USD",
  "occurredAt": "2026-09-18T17:02:11.004438300Z"
}
```

---

## API

All external requests should go through the API Gateway.

Base URL:

```text
http://localhost:9000
```

### Create Order

```http
POST /api/orders
```

Example request:

```json
{
  "customerId": "11111111-1111-1111-1111-111111111114",
  "totalAmount": 99.99,
  "currency": "USD"
}
```

Example response:

```json
{
  "id": "225f90f3-475c-4a3e-92a2-81d391bd2c0d",
  "customerId": "11111111-1111-1111-1111-111111111114",
  "status": "PENDING",
  "totalAmount": 99.99,
  "currency": "USD",
  "version": 0,
  "createdAt": "2026-09-18T17:02:11Z",
  "updatedAt": "2026-09-18T17:02:11Z"
}
```

Expected response:

```text
201 Created
```

---

### Get Order by ID

```http
GET /api/orders/{id}
```

Example:

```text
GET http://localhost:9000/api/orders/225f90f3-475c-4a3e-92a2-81d391bd2c0d
```

Expected response:

```text
200 OK
```

If the order does not exist:

```text
404 Not Found
```

---

## Kafka Event Flow

```text
POST /api/orders
       │
       ▼
OrderApplicationService
       │
       ├──────────────► OrderRepository
       │                     │
       │                     ▼
       │                PostgreSQL
       │
       ▼
OrderCreatedEvent
       │
       ▼
OrderEventPublisher
       │
       ▼
KafkaTemplate
       │
       │ key = orderId
       ▼
order-events
       │
       ▼
Kafka Consumer
```

---

## Testing

The project currently uses JUnit 5 and Mockito for unit testing.

`OrderApplicationServiceTest` verifies that order creation:

- saves the order through `OrderRepository`
- returns the expected `OrderResponse`
- creates an `OrderCreatedEvent`
- publishes the event through `OrderEventPublisher`
- publishes the correct order and customer information

Run tests with:

```bash
mvn test
```

Current test result:

```text
Tests run: 2
Failures: 0
Errors: 0
Skipped: 0

BUILD SUCCESS
```

---

## Local Infrastructure

Current Docker containers:

| Component | Port |
|---|---:|
| PostgreSQL | 5432 |
| Redis | 6379 |
| Kafka | 9092 |

Start infrastructure:

```bash
docker compose up -d
```

Check running containers:

```bash
docker ps
```

---

## Development Progress

### Day 1 — Infrastructure

- Created OrderFlow project structure
- Created Eureka Discovery Service
- Configured Docker Compose
- Started PostgreSQL
- Started Redis
- Started Apache Kafka
- Created `order-events` Kafka topic
- Verified Kafka Producer → Consumer communication

### Day 2 — Order Service

- Connected `order-service` to PostgreSQL
- Added Flyway
- Created database migration
- Created `orders` table
- Created `OrderEntity`
- Added optimistic locking using `@Version`
- Created `OrderRepository`
- Created `CreateOrderRequest`
- Created `OrderResponse`
- Created `OrderApplicationService`
- Implemented `POST /api/orders`
- Verified PostgreSQL persistence
- Added Bean Validation
- Added `GlobalExceptionHandler`

### Day 3 — Gateway & Event-Driven Communication

- Created `gateway-service`
- Registered Gateway with Eureka
- Configured Eureka-based routing
- Added `lb://order-service`
- Routed `/api/orders/**` through Gateway
- Implemented `GET /api/orders/{id}`
- Added `OrderNotFoundException`
- Added Kafka support to `order-service`
- Created `OrderCreatedEvent`
- Created `OrderEventPublisher`
- Configured Kafka JSON serialization
- Published events after successful order creation
- Used `orderId` as Kafka message key
- Verified Kafka events using Console Consumer
- Added ISO-8601 event timestamps
- Fixed order creation/update timestamps
- Added JUnit 5 and Mockito unit tests
- Verified Maven test build successfully

---

## Planned Architecture

Future development will expand OrderFlow into a multi-service distributed workflow.

Planned services and features include:

- Inventory Service
- Inventory reservation workflow
- Additional Kafka consumers and producers
- Order lifecycle events
- Redis caching
- Cache invalidation
- Transactional Outbox Pattern
- Idempotent event consumers
- Kafka retries
- Dead Letter Topics
- Saga / distributed workflow
- Resilience patterns
- Observability
- Distributed tracing
- Metrics
- Dockerized microservices
- Kubernetes deployment
- AWS deployment
- GitHub Actions CI/CD

---

## Planned Event Flow

```text
Client
  │
  ▼
API Gateway
  │
  ▼
Order Service
  │
  ├────────────► PostgreSQL
  │
  └────────────► Kafka
                    │
                    ▼
             Inventory Service
                    │
                    ▼
                PostgreSQL
                    │
                    ▼
             Inventory Events
                    │
                    ▼
                 Kafka
```

As the project evolves, direct database-and-Kafka dual writes will be replaced with a Transactional Outbox Pattern to improve reliability and consistency.

---

## Project Status

Current development status:

```text
Day 1  ✅ Infrastructure
Day 2  ✅ Order Service
Day 3  ✅ Gateway + Kafka Events
Day 4  ⏳ Inventory Service
```