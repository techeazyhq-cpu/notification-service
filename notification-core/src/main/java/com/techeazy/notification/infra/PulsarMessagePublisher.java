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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techeazy.notification.config.NotificationProperties;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.port.MessagePublisher;
import jakarta.annotation.PreDestroy;
import org.apache.pulsar.client.api.CompressionType;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** One producer per channel topic. Payload is a small versioned JSON envelope holding only the message id. */
@Component
public class PulsarMessagePublisher implements MessagePublisher {

    public record Envelope(int v, UUID messageId, Channel channel) {
        public static final int CURRENT_VERSION = 1;
    }

    private final PulsarClient client;
    private final NotificationProperties props;
    private final ObjectMapper mapper;
    private final Map<Channel, CompletableFuture<Producer<byte[]>>> producers = new ConcurrentHashMap<>();

    public PulsarMessagePublisher(PulsarClient client, NotificationProperties props, ObjectMapper mapper) {
        this.client = client;
        this.props = props;
        this.mapper = mapper;
    }

    public static String topicFor(NotificationProperties props, Channel channel) {
        return props.getPulsar().getTopicPrefix() + channel.topicSuffix();
    }

    @Override
    public CompletableFuture<Void> publish(Channel channel, UUID messageId, UUID clientId) {
        try {
            byte[] payload = mapper.writeValueAsBytes(new Envelope(Envelope.CURRENT_VERSION, messageId, channel));
            return producerFor(channel)
                    .thenCompose(producer -> producer.newMessage().key(clientId.toString()).value(payload).sendAsync())
                    .<Void>thenApply(id -> null)
                    .orTimeout(props.getPulsar().getPublishTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Creates a channel's producer without blocking; a failed creation is forgotten so the next publish tries again. */
    private CompletableFuture<Producer<byte[]>> producerFor(Channel channel) {
        CompletableFuture<Producer<byte[]>> future = producers.computeIfAbsent(channel, this::createProducer);
        future.whenComplete((producer, error) -> {
            if (error != null) {
                producers.remove(channel, future);
            }
        });
        return future;
    }

    private CompletableFuture<Producer<byte[]>> createProducer(Channel channel) {
        return client.newProducer()
                .topic(topicFor(props, channel))
                .producerName("notification-producer-" + channel.topicSuffix() + "-" + UUID.randomUUID())
                .compressionType(CompressionType.LZ4)
                .enableBatching(true)
                .blockIfQueueFull(true)
                .sendTimeout(props.getPulsar().getPublishTimeoutSeconds(), TimeUnit.SECONDS)
                .createAsync();
    }

    @PreDestroy
    void close() {
        producers.values().forEach(future -> future.thenAccept(Producer::closeAsync));
    }
}
