package com.techeazy.notification.dispatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techeazy.notification.config.NotificationProperties;
import com.techeazy.notification.dispatcher.DispatchService.Outcome;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.infra.PulsarMessagePublisher;
import com.techeazy.notification.infra.PulsarMessagePublisher.Envelope;
import jakarta.annotation.PreDestroy;
import org.apache.pulsar.client.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Subscribes to one topic per channel with a Shared subscription. Transient failures go to the
 * Pulsar retry topic with exponential delay; poison messages end up in the dead-letter topic.
 */
@Component
public class DispatchConsumers {

    private static final Logger log = LoggerFactory.getLogger(DispatchConsumers.class);

    private final PulsarClient client;
    private final NotificationProperties notificationProps;
    private final DispatcherProperties props;
    private final DispatchService dispatch;
    private final ObjectMapper mapper;
    private final List<Consumer<byte[]>> consumers = new ArrayList<>();

    public DispatchConsumers(PulsarClient client, NotificationProperties notificationProps, DispatcherProperties props,
                             DispatchService dispatch, ObjectMapper mapper) {
        this.client = client;
        this.notificationProps = notificationProps;
        this.props = props;
        this.dispatch = dispatch;
        this.mapper = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() throws PulsarClientException {
        for (Channel channel : Channel.values()) {
            String topic = PulsarMessagePublisher.topicFor(notificationProps, channel);
            for (int i = 0; i < props.getConsumersPerChannel(); i++) {
                consumers.add(client.newConsumer(Schema.BYTES)
                        .topic(topic)
                        .subscriptionName("dispatcher-" + channel.topicSuffix())
                        .subscriptionType(SubscriptionType.Shared)
                        .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                        .consumerName("dispatcher-" + channel.topicSuffix() + "-" + i)
                        .receiverQueueSize(100)
                        .negativeAckRedeliveryDelay(5, TimeUnit.SECONDS)
                        .enableRetry(true)
                        .deadLetterPolicy(DeadLetterPolicy.builder()
                                .maxRedeliverCount(props.getMaxAttempts() + 2)
                                .retryLetterTopic(topic + "-retry")
                                .deadLetterTopic(topic + "-dlq")
                                .build())
                        .messageListener((consumer, msg) -> handle(consumer, msg, channel))
                        .subscribe());
            }
            log.info("Started {} consumer(s) on {}", props.getConsumersPerChannel(), topic);
        }
    }

    private void handle(Consumer<byte[]> consumer, Message<byte[]> msg, Channel channel) {
        try {
            Envelope env = mapper.readValue(msg.getData(), Envelope.class);
            long deadline = System.currentTimeMillis() + props.getMaxRateLimitWaitSeconds() * 1000;
            while (true) {
                Outcome outcome = dispatch.process(env.messageId());
                switch (outcome) {
                    case Outcome.Done d -> consumer.acknowledge(msg);
                    case Outcome.Retry r -> consumer.reconsumeLater(msg, r.delay().toMillis(), TimeUnit.MILLISECONDS);
                    case Outcome.RateLimited r -> {
                        if (System.currentTimeMillis() + r.waitMillis() > deadline) {
                            consumer.negativeAcknowledge(msg); // hand back; another worker or a later delivery retries
                        } else {
                            Thread.sleep(Math.max(r.waitMillis(), 10)); // backpressure: hold the message, do not spin
                            continue;
                        }
                    }
                }
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            consumer.negativeAcknowledge(msg);
        } catch (Exception e) {
            log.error("Unexpected error handling broker message {} on {}; negative-acking", msg.getMessageId(), channel, e);
            consumer.negativeAcknowledge(msg);
        }
    }

    @PreDestroy
    void stop() {
        consumers.forEach(c -> c.closeAsync());
    }
}
