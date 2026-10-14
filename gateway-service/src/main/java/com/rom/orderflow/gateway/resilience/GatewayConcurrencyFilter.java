package com.rom.orderflow.gateway.resilience;

import io.micrometer.core.instrument.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.concurrent.Semaphore;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@Component
public class GatewayConcurrencyFilter implements GlobalFilter, Ordered {
    private final Map<String, Semaphore> permits;
    private final MeterRegistry metrics;
    private final ResilienceErrors errors;

    public GatewayConcurrencyFilter(MeterRegistry metrics, ResilienceErrors errors,
            @Value("${orderflow.resilience.max-concurrent-calls:64}") int concurrent) {
        if (concurrent < 1) throw new IllegalArgumentException("Concurrency must be positive");
        this.metrics = metrics;
        this.errors = errors;
        Map<String, Semaphore> limits = new HashMap<>();
        for (String name : List.of("order-service", "inventory-service")) {
            Semaphore limit = new Semaphore(concurrent);
            limits.put(name, limit);
            metrics.gauge("orderflow.gateway.concurrent.available", List.of(Tag.of("route", name)), limit, Semaphore::availablePermits);
        }
        permits = Map.copyOf(limits);
    }

    @Override public int getOrder() { return -2; } // Enclose NettyWriteResponseFilter (-1), including body/cancellation.
    @Override public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        Semaphore limit = route == null ? null : permits.get(route.getId());
        if (limit == null) return chain.filter(exchange);
        return Mono.defer(() -> {
            if (!limit.tryAcquire()) {
                metrics.counter("orderflow.gateway.rejected", "route", route.getId()).increment();
                return errors.write(exchange, route.getId(), "CONCURRENCY_LIMIT", HttpStatus.SERVICE_UNAVAILABLE);
            }
            return Mono.defer(() -> chain.filter(exchange)).doFinally(signal -> limit.release());
        });
    }
}
