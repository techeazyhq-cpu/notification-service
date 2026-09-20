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
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.infra.PulsarMessagePublisher;
import com.techeazy.notification.infra.PulsarMessagePublisher.Envelope;
import jakarta.annotation.PreDestroy;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.client.api.SubscriptionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reads each channel's dead-letter topic and records what arrives in the database. Without this, a message the broker
 * dead-lettered would sit in the topic and in the database in a half-finished state, unseen by operators.
 */
@Component
class DeadLetterConsumers {

    static final String SUBSCRIPTION = "dead-letter-recorder";

    private static final Logger LOG = LoggerFactory.getLogger(DeadLetterConsumers.class);

    private final PulsarClient client;
    private final NotificationProperties properties;
    private final DeadLetterRecorder recorder;
    private final ObjectMapper mapper;
    private final List<Consumer<byte[]>> consumers = new ArrayList<>();

    DeadLetterConsumers(PulsarClient client, NotificationProperties properties, DeadLetterRecorder recorder, ObjectMapper mapper) {
        this.client = client;
        this.properties = properties;
        this.recorder = recorder;
        this.mapper = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() throws PulsarClientException {
        for (Channel channel : Channel.values()) {
            String topic = PulsarMessagePublisher.topicFor(properties, channel) + "-dlq";
            consumers.add(client.newConsumer(Schema.BYTES)
                    .topic(topic)
                    .subscriptionName(SUBSCRIPTION)
                    .subscriptionType(SubscriptionType.Shared)
                    .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                    .negativeAckRedeliveryDelay(30, TimeUnit.SECONDS)
                    .messageListener((consumer, message) -> handle(consumer, message, channel))
                    .subscribe());
            LOG.info("Recording dead letters from {}", topic);
        }
    }

    void handle(Consumer<byte[]> consumer, Message<byte[]> message, Channel channel) {
        Envelope envelope;
        try {
            envelope = mapper.readValue(message.getData(), Envelope.class);
        } catch (java.io.IOException e) {
            LOG.error("Dropping an unreadable dead letter on {} (broker id {}): {}", channel, message.getMessageId(), e.getMessage());
            consumer.acknowledgeAsync(message);
            return;
        }
        try {
            recorder.record(envelope.messageId(), channel.name());
            consumer.acknowledge(message);
        } catch (Exception e) {
            LOG.error("Could not record a dead letter on {}; it will be delivered again shortly", channel, e);
            consumer.negativeAcknowledge(message);
        }
    }

    @PreDestroy
    void stop() {
        consumers.forEach(Consumer::closeAsync);
    }
}
