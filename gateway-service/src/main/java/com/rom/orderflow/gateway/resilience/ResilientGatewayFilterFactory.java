package com.rom.orderflow.gateway.resilience;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.*;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** One bulkhead and circuit per downstream; retries are inside the circuit. */
@Component
public class ResilientGatewayFilterFactory extends AbstractGatewayFilterFactory<ResilientGatewayFilterFactory.Config> {
    private final SpringCloudCircuitBreakerResilience4JFilterFactory circuits;
    private final RetryGatewayFilterFactory retries;
    private final MeterRegistry metrics;
    private final int attempts;
    private final Duration backoff;

    public ResilientGatewayFilterFactory(SpringCloudCircuitBreakerResilience4JFilterFactory circuits,
            RetryGatewayFilterFactory retries, MeterRegistry metrics,
            @Value("${orderflow.resilience.get-max-attempts:2}") int attempts,
            @Value("${orderflow.resilience.retry-backoff:200ms}") Duration backoff) {
        super(Config.class);
        if (attempts < 1 || attempts > 5 || backoff.isZero() || backoff.isNegative()) {
            throw new IllegalArgumentException("Resilience requires 1..5 attempts and positive backoff");
        }
        this.circuits = circuits;
        this.retries = retries;
        this.metrics = metrics;
        this.attempts = attempts;
        this.backoff = backoff;
    }

    @Override public GatewayFilter apply(Config config) {
        String name = config.getName();
        if (!Set.of("order-service", "inventory-service").contains(name)) throw new IllegalArgumentException("Unknown resilience route: " + name);
        var circuitConfig = new SpringCloudCircuitBreakerFilterFactory.Config().setName(name)
                .setFallbackUri("forward:/internal/resilience/" + name)
                .setStatusCodes(Set.of("500", "502", "503", "504"));
        circuitConfig.setRouteId(name);
        GatewayFilter circuit = circuits.apply(circuitConfig);
        var retryConfig = new RetryGatewayFilterFactory.RetryConfig().setRetries(Math.max(1, attempts - 1))
                .setMethods(HttpMethod.GET).setSeries()
                .setExceptions(java.io.IOException.class, java.util.concurrent.TimeoutException.class,
                        org.springframework.cloud.gateway.support.TimeoutException.class)
                .setStatuses(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.GATEWAY_TIMEOUT)
                .setBackoff(backoff, backoff.multipliedBy(8), 2, false);
        retryConfig.setRouteId(name);
        GatewayFilter retry = attempts == 1 ? (exchange, chain) -> chain.filter(exchange) : retries.apply(retryConfig);
        return (exchange, chain) -> Mono.defer(() -> {
            AtomicInteger calls = new AtomicInteger();
            String method = exchange.getRequest().getMethod() == HttpMethod.GET ? "GET" : "OTHER";
            return circuit.filter(exchange, outer -> retry.filter(outer, inner -> Mono.defer(() -> {
                metrics.counter("orderflow.gateway.attempts", "route", name, "method", method).increment();
                if (calls.getAndIncrement() > 0) metrics.counter("orderflow.gateway.retries", "route", name, "method", method).increment();
                return chain.filter(inner).onErrorMap(error -> {
                    if (!ResilienceErrors.timeout(error)) return error;
                    var timeout = new java.util.concurrent.TimeoutException("Downstream request timed out");
                    timeout.initCause(error);
                    return timeout;
                });
            })));
        });
    }

    public static class Config {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
}
