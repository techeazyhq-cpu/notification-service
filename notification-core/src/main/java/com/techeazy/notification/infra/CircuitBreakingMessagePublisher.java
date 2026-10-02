/*
 * Copyright 2026 Vasantha Kumar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Vasantha Kumar <vasantha.kumar@hotmail.com>
 */
package com.techeazy.notification.infra;

import com.techeazy.notification.domain.MessageCategory;
import com.techeazy.notification.config.NotificationProperties;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.port.BrokerUnavailableException;
import com.techeazy.notification.port.MessagePublisher;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Guards publishing to the broker with a circuit breaker named {@value #NAME} (ADR-032). While the broker answers,
 * every publish goes through. Once enough fail, the circuit opens and publishes fail at once with
 * {@link BrokerUnavailableException}: accepts answer immediately instead of each waiting out the publish timeout, and
 * the messages stay PENDING for the outbox sweeper. After a wait, a few probe publishes decide whether to close it.
 * Its state is exported as {@code resilience4j_circuitbreaker_state{name="broker"}}, next to the providers' breakers.
 */
@Primary
@Component
public class CircuitBreakingMessagePublisher implements MessagePublisher {

    public static final String NAME = "broker";

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakingMessagePublisher.class);

    private final MessagePublisher broker;
    private final CircuitBreaker circuitBreaker;

    @Autowired
    public CircuitBreakingMessagePublisher(PulsarMessagePublisher broker, NotificationProperties properties,
                                           MeterRegistry meters) {
        this((MessagePublisher) broker, properties, meters);
    }

    CircuitBreakingMessagePublisher(MessagePublisher broker, NotificationProperties properties, MeterRegistry meters) {
        this.broker = broker;
        NotificationProperties.Pulsar.CircuitBreaker settings = properties.getPulsar().getCircuitBreaker();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.getSlidingWindowSize())
                .minimumNumberOfCalls(settings.getMinimumNumberOfCalls())
                .failureRateThreshold(settings.getFailureRateThreshold())
                .waitDurationInOpenState(Duration.ofMillis(settings.getWaitDurationInOpenStateMs()))
                .permittedNumberOfCallsInHalfOpenState(settings.getPermittedCallsInHalfOpenState())
                .slowCallDurationThreshold(Duration.ofMillis(settings.getSlowCallDurationThresholdMs()))
                .slowCallRateThreshold(settings.getSlowCallRateThreshold())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build());
        this.circuitBreaker = registry.circuitBreaker(NAME);
        if (!settings.isEnabled()) {
            circuitBreaker.transitionToDisabledState();
        }
        circuitBreaker.getEventPublisher().onStateTransition(transition -> log.warn(
                "Circuit breaker for the message broker: {} -> {}",
                transition.getStateTransition().getFromState(), transition.getStateTransition().getToState()));
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
    }

    @Override
    public CompletableFuture<Void> publish(Channel channel, MessageCategory category, UUID messageId, UUID clientId) {
        if (!circuitBreaker.tryAcquirePermission()) {
            return CompletableFuture.failedFuture(new BrokerUnavailableException());
        }
        long started = circuitBreaker.getCurrentTimestamp();
        CompletableFuture<Void> published;
        try {
            published = broker.publish(channel, category, messageId, clientId);
        } catch (RuntimeException e) {
            circuitBreaker.onError(elapsedSince(started), circuitBreaker.getTimestampUnit(), e);
            return CompletableFuture.failedFuture(e);
        }
        return published.whenComplete((ignored, error) -> {
            if (error == null) {
                circuitBreaker.onSuccess(elapsedSince(started), circuitBreaker.getTimestampUnit());
            } else {
                circuitBreaker.onError(elapsedSince(started), circuitBreaker.getTimestampUnit(), error);
            }
        });
    }

    public CircuitBreaker.State state() {
        return circuitBreaker.getState();
    }

    CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    private long elapsedSince(long started) {
        return circuitBreaker.getCurrentTimestamp() - started;
    }
}
