package com.rom.orderflow.inventory.observability;

import io.micrometer.tracing.*;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@RequiredArgsConstructor
public class OutboxTracing {
    private final Tracer tracer;
    private final Propagator propagator;

    public String capture() {
        Span span = tracer.currentSpan();
        if (span == null) return null;
        Map<String, String> headers = new HashMap<>();
        propagator.inject(span.context(), headers, Map::put);
        return headers.get("traceparent");
    }

    public Scope restore(String parent, UUID orderId, UUID eventId, String eventType) {
        Map<String, String> headers = parent == null ? Map.of() : Map.of("traceparent", parent);
        Span span = propagator.extract(headers, Map::get).name("outbox.publish")
                .tag("orderId", orderId.toString()).tag("eventId", eventId.toString())
                .tag("eventType", eventType).start();
        return new Scope(span, tracer.withSpan(span));
    }

    public record Scope(Span span, Tracer.SpanInScope scope) implements AutoCloseable {
        public void close() {
            scope.close();
            span.end();
        }
    }
}
