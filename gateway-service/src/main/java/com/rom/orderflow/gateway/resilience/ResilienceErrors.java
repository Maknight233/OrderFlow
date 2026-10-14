package com.rom.orderflow.gateway.resilience;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Component
public class ResilienceErrors {
    private final ObjectMapper json;
    private final MeterRegistry metrics;

    public ResilienceErrors(ObjectMapper json, MeterRegistry metrics) {
        this.json = json;
        this.metrics = metrics;
    }

    public Mono<Void> write(ServerWebExchange exchange, String route, String code, HttpStatus status) {
        if (exchange.getResponse().isCommitted()) return Mono.error(new IllegalStateException("Response already committed"));
        metrics.counter("orderflow.gateway.fallbacks", "route", route, "reason", code).increment();
        if (status == HttpStatus.GATEWAY_TIMEOUT) metrics.counter("orderflow.gateway.timeouts", "route", route).increment();
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        byte[] body = json.writeValueAsBytes(Map.of("status", status.value(), "code", code,
                "message", status == HttpStatus.GATEWAY_TIMEOUT ? "Downstream request timed out" : "Downstream temporarily unavailable",
                "route", route, "timestamp", Instant.now().toString(), "requestId", exchange.getRequest().getId()));
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    static boolean timeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof io.netty.handler.timeout.TimeoutException
                    || cause instanceof ResponseStatusException status && status.getStatusCode().value() == 504) return true;
        }
        return false;
    }
}
