package com.techeazy.notification.dispatcher.provider;

import com.techeazy.notification.dispatcher.DispatcherProperties;
import com.techeazy.notification.dispatcher.provider.ChannelProvider.PermanentSendException;
import com.techeazy.notification.dispatcher.provider.ChannelProvider.TransientSendException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
class ResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(ResilienceConfig.class);

    /**
     * Only {@link TransientSendException} (timeouts, 5xx, throttling, connection errors) counts against a provider.
     * {@link PermanentSendException} means the recipient or content was bad, which says nothing about provider health.
     */
    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry(DispatcherProperties props, MeterRegistry meters) {
        DispatcherProperties.CircuitBreaker c = props.getCircuitBreaker();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(c.getSlidingWindowSize())
                .minimumNumberOfCalls(c.getMinimumNumberOfCalls())
                .failureRateThreshold(c.getFailureRateThreshold())
                .slowCallDurationThreshold(Duration.ofMillis(c.getSlowCallDurationMs()))
                .slowCallRateThreshold(c.getSlowCallRateThreshold())
                .waitDurationInOpenState(Duration.ofMillis(c.getWaitDurationInOpenStateMs()))
                .permittedNumberOfCallsInHalfOpenState(c.getPermittedCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(TransientSendException.class)
                .ignoreExceptions(PermanentSendException.class)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        registry.getEventPublisher().onEntryAdded(added -> added.getAddedEntry().getEventPublisher()
                .onStateTransition(t -> log.warn("Circuit breaker for provider '{}': {} -> {}",
                        t.getCircuitBreakerName(), t.getStateTransition().getFromState(), t.getStateTransition().getToState())));
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        return registry;
    }
}
