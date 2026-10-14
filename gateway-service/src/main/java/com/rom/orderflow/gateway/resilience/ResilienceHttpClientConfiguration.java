package com.rom.orderflow.gateway.resilience;

import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class ResilienceHttpClientConfiguration {
    @Bean HttpClientCustomizer disableTransportRetries() {
        // Reactor Netty's implicit retry must not replay a POST after a connection reset.
        return client -> client.disableRetry(true);
    }
}
