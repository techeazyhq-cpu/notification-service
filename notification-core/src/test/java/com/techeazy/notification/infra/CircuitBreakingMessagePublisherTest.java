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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * While the broker is failing, accepts must not each wait out the publish timeout (finding N3): the circuit opens and
 * publishes fail at once, leaving messages PENDING for the outbox sweeper (ADR-032).
 */
class CircuitBreakingMessagePublisherTest {

    private final MessagePublisher broker = mock(MessagePublisher.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private CircuitBreakingMessagePublisher publisher;

    @BeforeEach
    void setUp() {
        NotificationProperties properties = new NotificationProperties();
        NotificationProperties.Pulsar.CircuitBreaker settings = properties.getPulsar().getCircuitBreaker();
        settings.setSlidingWindowSize(4);
        settings.setMinimumNumberOfCalls(4);
        settings.setFailureRateThreshold(50);
        settings.setWaitDurationInOpenStateMs(60_000);
        settings.setPermittedCallsInHalfOpenState(1);
        publisher = new CircuitBreakingMessagePublisher(broker, properties, meters);
    }

    @Test
    void whileTheBrokerAnswersEveryPublishGoesThrough() {
        when(broker.publish(any(), any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        assertThat(publish()).isCompleted();

        assertThat(publisher.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void repeatedFailuresOpenTheCircuitAndLaterPublishesFailAtOnceWithoutTouchingTheBroker() {
        when(broker.publish(any(), any(), any(),
                any())).thenReturn(CompletableFuture.failedFuture(new TimeoutException()));
        for (int attempt = 0; attempt < 4; attempt++) {
            publish();
        }

        CompletableFuture<Void> refused = publish();

        assertThat(publisher.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(refused).isCompletedExceptionally();
        assertThat(refused.exceptionNow()).isInstanceOf(BrokerUnavailableException.class);
        verify(broker, times(4)).publish(any(), any(), any(), any());
    }

    @Test
    void aSuccessfulProbeAfterTheWaitClosesTheCircuitAgain() {
        when(broker.publish(any(), any(), any(),
                any())).thenReturn(CompletableFuture.failedFuture(new TimeoutException()));
        for (int attempt = 0; attempt < 4; attempt++) {
            publish();
        }
        publisher.circuitBreaker().transitionToHalfOpenState();
        when(broker.publish(any(), any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        assertThat(publish()).isCompleted();

        assertThat(publisher.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void aFailureRaisedBeforeAFutureExistsCountsAgainstTheBrokerToo() {
        when(broker.publish(any(), any(), any(), any())).thenThrow(new IllegalStateException("client closed"));

        assertThat(publish()).isCompletedExceptionally();

        assertThat(publisher.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void whenDisabledEveryPublishGoesStraightToTheBroker() {
        NotificationProperties properties = new NotificationProperties();
        properties.getPulsar().getCircuitBreaker().setEnabled(false);
        CircuitBreakingMessagePublisher unguarded = new CircuitBreakingMessagePublisher(broker, properties, meters);
        when(broker.publish(any(), any(), any(),
                any())).thenReturn(CompletableFuture.failedFuture(new TimeoutException()));

        for (int attempt = 0; attempt < 10; attempt++) {
            unguarded.publish(Channel.SMS, MessageCategory.TRANSACTIONAL, UUID.randomUUID(), UUID.randomUUID());
        }

        verify(broker, times(10)).publish(any(), any(), any(), any());
        assertThat(unguarded.state()).isEqualTo(CircuitBreaker.State.DISABLED);
    }

    @Test
    void everyServiceThatAsksForAPublisherGetsTheGuardedOne() {
        new ApplicationContextRunner()
                .withBean(PulsarMessagePublisher.class, () -> mock(PulsarMessagePublisher.class))
                .withBean(NotificationProperties.class)
                .withBean(SimpleMeterRegistry.class)
                .withUserConfiguration(CircuitBreakingMessagePublisher.class)
                .run(context -> assertThat(context.getBean(MessagePublisher.class))
                        .isInstanceOf(CircuitBreakingMessagePublisher.class));
    }

    @Test
    void theStateIsExportedAsTheSameMetricTheProviderBreakersUse() {
        assertThat(meters.find("resilience4j.circuitbreaker.state").tag("name", "broker").gauges()).isNotEmpty();
    }

    private CompletableFuture<Void> publish() {
        return publisher.publish(Channel.SMS, MessageCategory.TRANSACTIONAL, UUID.randomUUID(), UUID.randomUUID());
    }
}
