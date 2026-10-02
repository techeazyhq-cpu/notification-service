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
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
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

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        when(client.newConsumer(Schema.BYTES)).thenReturn(builder);
        when(builder.subscribe()).thenReturn(mock(Consumer.class));
        properties.setConsumersPerChannel(2);
        properties.setPriorityConsumersPerChannel(3);
    }

    @Test
    void eachChannelHasStandardAndPriorityConsumersOnSeparateTopicsAndSubscriptions() throws Exception {
        new DispatchConsumers(client, new NotificationProperties(), properties, mock(DispatchService.class),
                new ObjectMapper(), mock(ProviderRegistry.class), ObservationRegistry.NOOP).start();

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
        new DispatchConsumers(client, new NotificationProperties(), properties, mock(DispatchService.class),
                new ObjectMapper(), mock(ProviderRegistry.class), ObservationRegistry.NOOP).start();

        ArgumentCaptor<DeadLetterPolicy> policies = ArgumentCaptor.forClass(DeadLetterPolicy.class);
        verify(builder, atLeastOnce()).deadLetterPolicy(policies.capture());

        assertThat(policies.getAllValues()).extracting(DeadLetterPolicy::getDeadLetterTopic)
                .contains("persistent://public/default/notification-sms-priority-dlq",
                        "persistent://public/default/notification-sms-dlq");
        verify(builder, atLeastOnce()).messageListener(any());
    }
}
