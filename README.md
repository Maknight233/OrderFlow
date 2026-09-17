# OrderFlow

OrderFlow is a distributed order processing platform built with Java 21, Spring Boot, Spring Cloud, PostgreSQL, Kafka, Redis, and Docker.

The project is designed to demonstrate production-oriented microservice patterns such as service discovery, API gateway routing, event-driven communication, database migrations, optimistic locking, caching, and eventually transactional outbox and distributed workflow patterns.

## Current Architecture

- Spring Boot
- Spring Cloud Gateway
- Eureka Service Discovery
- Maven Multi-Module
- Java 21

## Services

- discovery-service
- gateway-service
- order-service

## Current Request Flow

Client
→ API Gateway
→ Eureka Service Discovery
→ Order Service

## Current Architecture

```text
                        ┌─────────────────────┐
                        │    Eureka Server    │
                        │        :8761        │
                        └──────────┬──────────┘
                                   │
                        Service Registration
                                   │
              ┌────────────────────┴────────────────────┐
              │                                         │
     ┌────────▼────────┐                       ┌────────▼────────┐
     │ Gateway Service │                       │  Order Service  │
     │      :9000      │                       │      :8081      │
     └─────────────────┘                       └────────┬────────┘
                                                      │
                                            ┌─────────▼─────────┐
                                            │    PostgreSQL     │
                                            │      :5432        │
                                            └───────────────────┘

                         Infrastructure

              ┌─────────────────┐   ┌─────────────────┐
              │      Redis      │   │      Kafka      │
              │      :6379      │   │      :9092      │
              └─────────────────┘   └─────────────────┘