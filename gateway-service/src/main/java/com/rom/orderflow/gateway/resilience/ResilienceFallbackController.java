package com.rom.orderflow.gateway.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Set;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR;

@RestController
public class ResilienceFallbackController {
    private final ResilienceErrors errors;
    public ResilienceFallbackController(ResilienceErrors errors) { this.errors = errors; }

    @RequestMapping("/internal/resilience/{route}")
    public Mono<Void> fallback(@PathVariable String route, ServerWebExchange exchange) {
        Throwable error = exchange.getAttribute(CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        if (error == null || !Set.of("order-service", "inventory-service").contains(route)) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        if (ResilienceErrors.timeout(error)) return errors.write(exchange, route, "DOWNSTREAM_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT);
        String code = error instanceof CallNotPermittedException ? "CIRCUIT_OPEN" : "DOWNSTREAM_UNAVAILABLE";
        return errors.write(exchange, route, code, HttpStatus.SERVICE_UNAVAILABLE);
    }
}
