package com.techeazy.notification.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techeazy.notification.config.NotificationProperties;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.port.MessagePublisher;
import jakarta.annotation.PreDestroy;
import org.apache.pulsar.client.api.CompressionType;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** One producer per channel topic. Payload is a small versioned JSON envelope holding only the message id. */
@Component
public class PulsarMessagePublisher implements MessagePublisher {

    public record Envelope(int v, UUID messageId, Channel channel) {
        public static final int CURRENT_VERSION = 1;
    }

    private final PulsarClient client;
    private final NotificationProperties props;
    private final ObjectMapper mapper;
    private final Map<Channel, Producer<byte[]>> producers = new ConcurrentHashMap<>();

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
            return producers.computeIfAbsent(channel, this::createProducer)
                    .newMessage()
                    .key(clientId.toString())
                    .value(payload)
                    .sendAsync()
                    .thenAccept(id -> { /* only completion matters; the broker message id is not needed */ });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private Producer<byte[]> createProducer(Channel channel) {
        try {
            return client.newProducer()
                    .topic(topicFor(props, channel))
                    .producerName("notification-producer-" + channel.topicSuffix() + "-" + UUID.randomUUID())
                    .compressionType(CompressionType.LZ4)
                    .enableBatching(true)
                    .blockIfQueueFull(true)
                    .create();
        } catch (PulsarClientException e) {
            throw new IllegalStateException("Cannot create Pulsar producer for " + channel, e);
        }
    }

    @PreDestroy
    void close() {
        producers.values().forEach(Producer::closeAsync);
    }
}
