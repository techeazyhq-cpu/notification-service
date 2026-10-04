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

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
 *
 * <p>Every consumer is drained by a worker thread of its own. Handling blocks (a provider call, a rate-limit or
 * outage hold), and the Pulsar client runs all message listeners on one shared thread by default, so listeners
 * would serialise every lane and channel behind whichever message is held: a one-time password would wait behind a
 * rate-limited bulk message, and an SMS outage would stall e-mail (ADR-033).
 */
@Component
public class DispatchConsumers {

    private static final Logger log = LoggerFactory.getLogger(DispatchConsumers.class);
    private static final int RECEIVE_POLL_MS = 500;
    private static final String NAME_PREFIX = "dispatcher-";
    private static final long STOP_WAIT_MS = 5_000;

    private final PulsarClient client;
    private final NotificationProperties notificationProps;
    private final DispatcherProperties props;
    private final DispatchService dispatch;
    private final ObjectMapper mapper;
    private final ProviderRegistry providers;
    private final ObservationRegistry observations;
    private final List<Consumer<byte[]>> consumers = new ArrayList<>();
    private final Map<Channel, List<Consumer<byte[]>>> byChannel = new ConcurrentHashMap<>();
    private final Set<Channel> pausedChannels = ConcurrentHashMap.newKeySet();
    private final List<Thread> workers = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;

    public DispatchConsumers(PulsarClient client, NotificationProperties notificationProps, DispatcherProperties props,
                             DispatchService dispatch, ObjectMapper mapper, ProviderRegistry providers,
                             ObservationRegistry observations) {
        this.providers = providers;
        this.observations = observations;
        this.client = client;
        this.notificationProps = notificationProps;
        this.props = props;
        this.dispatch = dispatch;
        this.mapper = mapper;
    }

    /**
     * Subscribes to each channel's standard topic and, with consumers of its own, to its priority topic for one-time
     * passwords (ADR-033). Both lanes of a channel are paused and resumed together with its providers.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() throws PulsarClientException {
        for (Channel channel : Channel.values()) {
            List<Consumer<byte[]>> forChannel = new CopyOnWriteArrayList<>();
            subscribe(channel, false, props.getConsumersPerChannel(), forChannel);
            subscribe(channel, true, props.getPriorityConsumersPerChannel(), forChannel);
            byChannel.put(channel, forChannel);
        }
    }

    private void subscribe(Channel channel, boolean priority, int count, List<Consumer<byte[]>> forChannel)
            throws PulsarClientException {
        String topic = PulsarMessagePublisher.topicFor(notificationProps, channel, priority);
        String lane = channel.topicSuffix() + (priority ? PulsarMessagePublisher.PRIORITY_TOPIC_SUFFIX : "");
        for (int i = 0; i < count; i++) {
            Consumer<byte[]> created = client.newConsumer(Schema.BYTES)
                    .topic(topic)
                    .subscriptionName(NAME_PREFIX + lane)
                    .subscriptionType(SubscriptionType.Shared)
                    .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                    .consumerName(NAME_PREFIX + lane + "-" + i)
                    .receiverQueueSize(100)
                    .negativeAckRedeliveryDelay(5, TimeUnit.SECONDS)
                    .enableRetry(true)
                    .deadLetterPolicy(DeadLetterPolicy.builder()
                            .maxRedeliverCount(props.getMaxAttempts() + 2)
                            .retryLetterTopic(topic + "-retry")
                            .deadLetterTopic(topic + "-dlq")
                            .build())
                    .subscribe();
            consumers.add(created);
            forChannel.add(created);
            Thread worker = Thread.ofPlatform().name(NAME_PREFIX + lane + "-" + i)
                    .unstarted(() -> drain(created, channel));
            workers.add(worker);
            worker.start();
        }
        log.info("Started {} consumer(s) on {}", count, topic);
    }

    /** Receives and handles one message at a time until the consumers are stopped. */
    private void drain(Consumer<byte[]> consumer, Channel channel) {
        while (running) {
            Message<byte[]> msg;
            try {
                msg = consumer.receive(RECEIVE_POLL_MS, TimeUnit.MILLISECONDS);
            } catch (PulsarClientException.AlreadyClosedException e) {
                return;
            } catch (PulsarClientException e) {
                if (!running || Thread.currentThread().isInterrupted()) {
                    return;
                }
                log.warn("Receiving from {} failed; trying again: {}", consumer.getTopic(), e.toString());
                continue;
            }
            if (msg != null) {
                handle(consumer, msg, channel);
            }
        }
    }

    private void handle(Consumer<byte[]> consumer, Message<byte[]> msg, Channel channel) {
        Observation handling = BrokerMessageObservation.receiving(msg, channel, observations);
        handling.observe(() -> handleWithin(handling, consumer, msg, channel));
    }

    private void handleWithin(Observation handling, Consumer<byte[]> consumer, Message<byte[]> msg, Channel channel) {
        try {
            Envelope env = mapper.readValue(msg.getData(), Envelope.class);
            BrokerMessageObservation.identify(handling, env.messageId());
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
            handling.error(e);
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

    /**
     * Stops the workers first, so a message being held is handed back to the broker rather than abandoned, then
     * closes the consumers.
     */
    @PreDestroy
    void stop() {
        running = false;
        workers.forEach(Thread::interrupt);
        for (Thread worker : workers) {
            try {
                worker.join(STOP_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        consumers.forEach(Consumer::closeAsync);
    }
}
