# OrderFlow

OrderFlow is a distributed order processing platform built with Java 21, Spring Boot, Spring Cloud, PostgreSQL, Kafka, Redis, and Docker.

The project is designed to demonstrate production-oriented microservice patterns including service discovery, API gateway routing, event-driven communication, transactional outbox, consumer idempotency, optimistic locking, retry handling, concurrent-safe background workers, and Saga-style compensation.

---

## Architecture

```text
                              ┌─────────────────────┐
                              │    Eureka Server    │
                              │        :8761        │
                              └──────────┬──────────┘
                                         │
                               Service Registration
                                         │
                    ┌────────────────────┼────────────────────┐
                    │                    │                    │
                    ▼                    ▼                    ▼
          ┌─────────────────┐   ┌─────────────────┐  ┌─────────────────┐
          │ Gateway Service │   │  Order Service  │  │Inventory Service│
          │      :9000      │   │      :8081      │  │      :8082      │
          └────────┬────────┘   └────────┬────────┘  └────────┬────────┘
                   │                     │                    │
                   │                     │                    │
             HTTP / REST                 │                    │
                   │                     │                    │
                   └──────────────► Order API                 │
                                         │                    │
                                         ▼                    ▼
                                  ┌──────────────┐     ┌──────────────┐
                                  │ PostgreSQL   │     │ PostgreSQL   │
                                  │   orders     │     │  inventory   │
                                  │outbox_events │     │reservations  │
                                  └──────┬───────┘     └──────┬───────┘
                                         │                    │
                                         │ Transactional      │
                                         │ Outbox             │
                                         ▼                    │
                                  ┌──────────────┐             │
                                  │    Kafka     │◄────────────┘
                                  │    :9092     │
                                  └──────┬───────┘
                                         │
                     ┌───────────────────┴───────────────────┐
                     │                                       │
                     ▼                                       ▼
              order-events                           inventory-events
                     │                                       │
                     ▼                                       ▼
             Inventory Service                       Order Service