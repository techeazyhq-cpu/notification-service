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

import com.techeazy.notification.dispatcher.provider.ProviderRegistry;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private final ProviderRegistry providers;
    private final List<Consumer<byte[]>> consumers = new ArrayList<>();
    private final Map<Channel, List<Consumer<byte[]>>> byChannel = new ConcurrentHashMap<>();
    private final Set<Channel> pausedChannels = ConcurrentHashMap.newKeySet();

    public DispatchConsumers(PulsarClient client, NotificationProperties notificationProps, DispatcherProperties props,
                             DispatchService dispatch, ObjectMapper mapper, ProviderRegistry providers) {
        this.providers = providers;
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
            List<Consumer<byte[]>> forChannel = new CopyOnWriteArrayList<>();
            for (int i = 0; i < props.getConsumersPerChannel(); i++) {
                Consumer<byte[]> created = client.newConsumer(Schema.BYTES)
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
                        .subscribe();
                consumers.add(created);
                forChannel.add(created);
            }
            byChannel.put(channel, forChannel);
            log.info("Started {} consumer(s) on {}", props.getConsumersPerChannel(), topic);
        }
    }

    private void handle(Consumer<byte[]> consumer, Message<byte[]> msg, Channel channel) {
        try {
            Envelope env = mapper.readValue(msg.getData(), Envelope.class);
            Outcome outcome = processWithBackpressure(env.messageId());
            if (outcome instanceof Outcome.Done) {
                consumer.acknowledge(msg);
            } else if (outcome instanceof Outcome.Retry(Duration delay)) {
                consumer.reconsumeLater(msg, delay.toMillis(), TimeUnit.MILLISECONDS);
            } else {
                consumer.negativeAcknowledge(msg); // rate-limit wait budget spent: hand back for another worker or a later delivery
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            consumer.negativeAcknowledge(msg);
        } catch (Exception e) {
            log.error("Unexpected error handling broker message {} on {}; negative-acking", msg.getMessageId(), channel, e);
            consumer.negativeAcknowledge(msg);
        }
    }

    /**
     * Processes the message, holding it (backpressure, no spinning) while it is rate limited or every provider's
     * circuit is open. Returns the first outcome that needs the broker to act on it.
     */
    private Outcome processWithBackpressure(UUID messageId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + props.getMaxRateLimitWaitSeconds() * 1000;
        Outcome outcome = dispatch.process(messageId);
        long wait = holdMillis(outcome, deadline);
        while (wait >= 0) {
            Thread.sleep(Math.max(wait, 10));
            outcome = dispatch.process(messageId);
            wait = holdMillis(outcome, deadline);
        }
        return outcome;
    }

    /**
     * How long to keep holding the message, or -1 when the outcome is final. Circuit-open holds have no deadline and
     * are never nacked: redelivery counts would push a healthy message into the dead-letter topic during a long outage.
     */
    private static long holdMillis(Outcome outcome, long deadline) {
        if (outcome instanceof Outcome.Unavailable(long waitMillis)) return waitMillis;
        if (outcome instanceof Outcome.RateLimited(long waitMillis) && System.currentTimeMillis() + waitMillis <= deadline) {
            return waitMillis;
        }
        return -1;
    }

    /**
     * While every provider of a channel is circuit-open, pause that channel's consumers so the backlog waits in Pulsar
     * (durable, and visible as backlog) instead of being prefetched into this process. Resumed as soon as any
     * provider leaves the OPEN state. Idempotent, so it also covers admin config changes that add a healthy provider.
     */
    @Scheduled(fixedDelay = 1000)
    void reconcilePausedChannels() {
        for (Channel channel : Channel.values()) {
            List<Consumer<byte[]>> list = byChannel.get(channel);
            if (list == null) continue;
            boolean available = providers.isAvailable(channel);
            if (!available && pausedChannels.add(channel)) {
                list.forEach(Consumer::pause);
                log.warn("Paused {} consumers: all providers unavailable", channel);
            } else if (available && pausedChannels.remove(channel)) {
                list.forEach(Consumer::resume);
                log.info("Resumed {} consumers: a provider is available again", channel);
            }
        }
    }

    @PreDestroy
    void stop() {
        consumers.forEach(Consumer::closeAsync);
    }
}
