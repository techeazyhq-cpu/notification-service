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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techeazy.notification.config.NotificationProperties;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.port.MessagePublisher;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.SenderContext;
import jakarta.annotation.PreDestroy;
import org.apache.pulsar.client.api.CompressionType;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * One producer per channel topic. Payload is a small versioned JSON envelope holding only the message id. Each publish
 * is a {@value #PUBLISH_OBSERVATION} observation whose trace context travels in the message properties (W3C
 * {@code traceparent}), so the dispatcher continues the trace of the request that accepted the message (see ADR-023).
 */
@Component
public class PulsarMessagePublisher implements MessagePublisher {

    public static final String PUBLISH_OBSERVATION = "notification.publish";
    public static final String PRIORITY_TOPIC_SUFFIX = "-priority";

    public record Envelope(int v, UUID messageId, Channel channel) {
        public static final int CURRENT_VERSION = 1;
    }

    private final PulsarClient client;
    private final NotificationProperties props;
    private final ObjectMapper mapper;
    private final ObservationRegistry observations;
    private final Map<String, CompletableFuture<Producer<byte[]>>> producers = new ConcurrentHashMap<>();

    public PulsarMessagePublisher(PulsarClient client, NotificationProperties props, ObjectMapper mapper,
                                  ObservationRegistry observations) {
        this.client = client;
        this.props = props;
        this.mapper = mapper;
        this.observations = observations;
    }

    public static String topicFor(NotificationProperties props, Channel channel) {
        return props.getPulsar().getTopicPrefix() + channel.topicSuffix();
    }

    /** The topic a message travels on: one-time passwords have their own per channel, so no backlog delays them. */
    public static String topicFor(NotificationProperties props, Channel channel, boolean priority) {
        return priority ? topicFor(props, channel) + PRIORITY_TOPIC_SUFFIX : topicFor(props, channel);
    }

    @Override
    public CompletableFuture<Void> publish(Channel channel, MessageCategory category, UUID messageId, UUID clientId) {
        Map<String, String> traceProperties = new HashMap<>();
        Observation publishing = publishing(channel, messageId, traceProperties).start();
        try {
            byte[] payload = mapper.writeValueAsBytes(new Envelope(Envelope.CURRENT_VERSION, messageId, channel));
            return producerFor(topicFor(props, channel, category.isPriority()))
                    .thenCompose(producer -> producer.newMessage().key(clientId.toString()).value(payload)
                            .properties(traceProperties).sendAsync())
                    .<Void>thenApply(id -> null)
                    .orTimeout(props.getPulsar().getPublishTimeoutSeconds(), TimeUnit.SECONDS)
                    .whenComplete((ignored, error) -> finish(publishing, error));
        } catch (Exception e) {
            finish(publishing, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Started on the calling thread, so it is a child of the request being handled; the tracing handler writes the
     * trace context into {@code traceProperties}, which become the message's properties.
     */
    private Observation publishing(Channel channel, UUID messageId, Map<String, String> traceProperties) {
        SenderContext<Map<String, String>> context = new SenderContext<>(Map::put);
        context.setCarrier(traceProperties);
        context.setRemoteServiceName("pulsar");
        return Observation.createNotStarted(PUBLISH_OBSERVATION, () -> context, observations)
                .contextualName("notification publish")
                .lowCardinalityKeyValue("channel", channel.name())
                .highCardinalityKeyValue("notification.message_id", messageId.toString());
    }

    private static void finish(Observation publishing, Throwable error) {
        if (error != null) {
            publishing.error(error);
        }
        publishing.stop();
    }

    /** Creates a topic's producer without blocking; a failed creation is forgotten so the next publish tries again. */
    private CompletableFuture<Producer<byte[]>> producerFor(String topic) {
        CompletableFuture<Producer<byte[]>> future = producers.computeIfAbsent(topic, this::createProducer);
        future.whenComplete((producer, error) -> {
            if (error != null) {
                producers.remove(topic, future);
            }
        });
        return future;
    }

    private CompletableFuture<Producer<byte[]>> createProducer(String topic) {
        return client.newProducer()
                .topic(topic)
                .producerName("notification-producer-" + topic.substring(topic.lastIndexOf('/') + 1) + "-"
                        + UUID.randomUUID())
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
