package com.rom.orderflow.gateway.resilience;

import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.*;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class ResilienceHttpClientConfiguration {
    @Bean HttpClientCustomizer disableTransportRetries() {
        // Reactor Netty's implicit retry must not replay a POST after a connection reset.
        return client -> client.disableRetry(true);
    }

    @Bean
    @Profile("docker")
    HttpClientCustomizer refreshDockerDns() {
        // A restarted Compose service can receive a new IP while retaining its DNS name.
        return client -> client.resolver(spec -> spec.cacheMaxTimeToLive(Duration.ofSeconds(5)));
    }
}
