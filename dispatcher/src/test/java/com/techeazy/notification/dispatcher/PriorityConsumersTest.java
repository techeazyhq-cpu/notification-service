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
package com.techeazy.notification.dispatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techeazy.notification.config.NotificationProperties;
import com.techeazy.notification.dispatcher.provider.ProviderRegistry;
import com.techeazy.notification.domain.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.DeadLetterPolicy;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import com.techeazy.notification.infra.PulsarMessagePublisher.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every channel has a priority topic for one-time passwords with consumers of its own, so an OTP never waits behind
 * a bulk backlog on the standard topic (ADR-033); its dead letters are recorded like any other.
 */
class PriorityConsumersTest {

    private final PulsarClient client = mock(PulsarClient.class);
    @SuppressWarnings("unchecked")
    private final ConsumerBuilder<byte[]> builder = mock(ConsumerBuilder.class, Mockito.RETURNS_SELF);
    private final DispatcherProperties properties = new DispatcherProperties();
    private final Map<String, Consumer<byte[]>> consumersByTopic = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private String subscribingTo;
    private DispatchConsumers started;

    @BeforeEach
    void setUp() throws Exception {
        when(client.newConsumer(Schema.BYTES)).thenReturn(builder);
        when(builder.topic(anyString())).thenAnswer(invocation -> {
            subscribingTo = invocation.getArgument(0);
            return builder;
        });
        when(builder.subscribe()).thenAnswer(invocation ->
                consumersByTopic.computeIfAbsent(subscribingTo, topic -> idleConsumer()));
        properties.setConsumersPerChannel(2);
        properties.setPriorityConsumersPerChannel(3);
    }

    @AfterEach
    void stopWorkers() {
        if (started != null) {
            started.stop();
        }
    }

    @Test
    void eachChannelHasStandardAndPriorityConsumersOnSeparateTopicsAndSubscriptions() throws Exception {
        start(mock(DispatchService.class));

        ArgumentCaptor<String> topics = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subscriptions = ArgumentCaptor.forClass(String.class);
        verify(builder, atLeastOnce()).topic(topics.capture());
        verify(builder, atLeastOnce()).subscriptionName(subscriptions.capture());

        int channels = Channel.values().length;
        assertThat(topics.getAllValues().stream().filter(topic -> topic.endsWith("-priority"))).hasSize(3 * channels);
        assertThat(topics.getAllValues().stream().filter(topic -> !topic.endsWith("-priority"))).hasSize(2 * channels);
        assertThat(topics.getAllValues()).contains("persistent://public/default/notification-sms-priority",
                "persistent://public/default/notification-sms");
        assertThat(subscriptions.getAllValues()).contains("dispatcher-sms-priority", "dispatcher-sms");
    }

    @Test
    void deadLettersFromThePriorityTopicsAreRecordedToo() throws Exception {
        new DeadLetterConsumers(client, new NotificationProperties(),
                new DeadLetterRecorder(mock(com.techeazy.notification.persistence.NotificationMessageRepository.class),
                        new SimpleMeterRegistry()), new ObjectMapper()).start();

        ArgumentCaptor<String> topics = ArgumentCaptor.forClass(String.class);
        verify(builder, atLeastOnce()).topic(topics.capture());

        assertThat(topics.getAllValues()).containsAll(Arrays.stream(Channel.values())
                .map(channel -> "persistent://public/default/notification-" + channel.topicSuffix() + "-priority-dlq")
                .toList());
        assertThat(topics.getAllValues()).hasSize(2 * Channel.values().length);
    }

    @Test
    void theRetryAndDeadLetterTopicsOfThePriorityLaneAreItsOwn() throws Exception {
        start(mock(DispatchService.class));

        ArgumentCaptor<DeadLetterPolicy> policies = ArgumentCaptor.forClass(DeadLetterPolicy.class);
        verify(builder, atLeastOnce()).deadLetterPolicy(policies.capture());

        assertThat(policies.getAllValues()).extracting(DeadLetterPolicy::getDeadLetterTopic)
                .contains("persistent://public/default/notification-sms-priority-dlq",
                        "persistent://public/default/notification-sms-dlq");
    }

    @Test
    void aHeldStandardMessageDoesNotDelayAOneTimePassword() throws Exception {
        properties.setConsumersPerChannel(1);
        properties.setPriorityConsumersPerChannel(1);
        UUID bulk = UUID.randomUUID();
        UUID otp = UUID.randomUUID();
        Consumer<byte[]> standard = deliveringOnce(bulk);
        Consumer<byte[]> priority = deliveringOnce(otp);
        consumersByTopic.put("persistent://public/default/notification-sms", standard);
        consumersByTopic.put("persistent://public/default/notification-sms-priority", priority);
        CountDownLatch bulkHeld = new CountDownLatch(1);
        CountDownLatch releaseBulk = new CountDownLatch(1);
        DispatchService dispatch = mock(DispatchService.class);
        when(dispatch.process(bulk)).thenAnswer(invocation -> {
            bulkHeld.countDown();
            releaseBulk.await();
            return new DispatchService.Outcome.Done();
        });
        when(dispatch.process(otp)).thenReturn(new DispatchService.Outcome.Done());

        start(dispatch);

        assertThat(bulkHeld.await(5, TimeUnit.SECONDS)).isTrue();
        verify(priority, timeout(5_000)).acknowledge(any(Message.class));
        verify(standard, never()).acknowledge(any(Message.class));
        releaseBulk.countDown();
        verify(standard, timeout(5_000)).acknowledge(any(Message.class));
    }

    @Test
    void consumersAreDrainedByWorkersOfTheirOwnNotByTheSharedListenerThread() throws Exception {
        start(mock(DispatchService.class));

        verify(builder, never()).messageListener(any());
        int perChannel = properties.getConsumersPerChannel() + properties.getPriorityConsumersPerChannel();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(Thread.getAllStackTraces().keySet())
                .filteredOn(thread -> thread.getName().startsWith("dispatcher-sms"))
                .hasSize(perChannel));
    }

    @Test
    void stoppingEndsTheWorkersAndHandsAHeldMessageBack() throws Exception {
        UUID held = UUID.randomUUID();
        Consumer<byte[]> standard = deliveringOnce(held);
        consumersByTopic.put("persistent://public/default/notification-sms", standard);
        properties.setConsumersPerChannel(1);
        CountDownLatch holding = new CountDownLatch(1);
        DispatchService dispatch = mock(DispatchService.class);
        when(dispatch.process(held)).thenAnswer(invocation -> {
            holding.countDown();
            return new DispatchService.Outcome.Unavailable(60_000);
        });
        start(dispatch);
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

        started.stop();
        started = null;

        verify(standard).negativeAcknowledge(any(Message.class));
        verify(standard).closeAsync();
        assertThat(Thread.getAllStackTraces().keySet())
                .noneMatch(thread -> thread.getName().startsWith("dispatcher-"));
    }

    private void start(DispatchService dispatch) throws Exception {
        started = new DispatchConsumers(client, new NotificationProperties(), properties, dispatch, mapper,
                mock(ProviderRegistry.class), ObservationRegistry.NOOP);
        started.start();
    }

    @SuppressWarnings("unchecked")
    private static Consumer<byte[]> idleConsumer() {
        Consumer<byte[]> consumer = mock(Consumer.class);
        try {
            when(consumer.receive(anyInt(), any(TimeUnit.class))).thenAnswer(invocation -> idle());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return consumer;
    }

    @SuppressWarnings("unchecked")
    private Consumer<byte[]> deliveringOnce(UUID messageId) throws Exception {
        Message<byte[]> message = mock(Message.class);
        when(message.getData()).thenReturn(mapper.writeValueAsBytes(
                new Envelope(Envelope.CURRENT_VERSION, messageId, Channel.SMS)));
        when(message.getMessageId()).thenReturn(MessageId.earliest);
        Consumer<byte[]> consumer = mock(Consumer.class);
        AtomicBoolean delivered = new AtomicBoolean();
        when(consumer.receive(anyInt(), any(TimeUnit.class))).thenAnswer(invocation ->
                delivered.compareAndSet(false, true) ? message : idle());
        return consumer;
    }

    /** Waits like an empty receive; an interrupt surfaces as the Pulsar client reports it. */
    private static Message<byte[]> idle() throws PulsarClientException {
        try {
            Thread.sleep(20);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PulsarClientException(e);
        }
    }
}
