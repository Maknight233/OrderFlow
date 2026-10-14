package com.rom.orderflow.gateway.resilience;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

class GatewayConcurrencyFilterTest {
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final GatewayConcurrencyFilter filter = new GatewayConcurrencyFilter(metrics,
            new ResilienceErrors(JsonMapper.builder().build(), metrics), 1);

    @Test void synchronousDownstreamErrorDoesNotLeakPermission() {
        StepVerifier.create(filter.filter(exchange(), ignored -> { throw new IllegalStateException("sync failure"); }))
                .verifyError(IllegalStateException.class);
        assertEquals(1, available());
        StepVerifier.create(filter.filter(exchange(), ignored -> Mono.empty())).verifyComplete();
        assertEquals(1, available());
    }
    @Test void cancellationReleasesPermission() {
        StepVerifier.create(filter.filter(exchange(), ignored -> Mono.never())).then(() -> assertEquals(0, available()))
                .thenCancel().verify();
        assertEquals(1, available());
    }
    @Test void saturationDoesNotDispatchOrWaitAndReleaseIsBalanced() {
        AtomicInteger calls = new AtomicInteger();
        var running = filter.filter(exchange(), ignored -> { calls.incrementAndGet(); return Mono.never(); }).subscribe();
        var rejected = exchange();
        StepVerifier.create(filter.filter(rejected, ignored -> { calls.incrementAndGet(); return Mono.empty(); })).verifyComplete();
        assertEquals(503, rejected.getResponse().getStatusCode().value());
        assertEquals(1, calls.get());
        assertEquals(0, available());
        running.dispose();
        assertEquals(1, available());
    }
    private double available() { return metrics.get("orderflow.gateway.concurrent.available").tag("route", "order-service").gauge().value(); }
    private MockServerWebExchange exchange() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders/test"));
        Route route = mock(Route.class);
        when(route.getId()).thenReturn("order-service");
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
        return exchange;
    }
}
