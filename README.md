# OrderFlow

OrderFlow is a distributed order processing platform built with Spring Boot and Spring Cloud.

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