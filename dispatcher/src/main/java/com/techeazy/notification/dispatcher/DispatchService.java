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

import com.techeazy.notification.error.ErrorCode;
import com.techeazy.notification.application.RateLimitService;
import com.techeazy.notification.application.TemplateRenderer;
import com.techeazy.notification.dispatcher.provider.ChannelProvider.Outbound;
import com.techeazy.notification.dispatcher.provider.ChannelProvider.PermanentSendException;
import com.techeazy.notification.dispatcher.provider.ChannelProvider.SendResult;
import com.techeazy.notification.dispatcher.provider.ProviderRegistry;
import com.techeazy.notification.domain.*;
import com.techeazy.notification.persistence.MessageEventLog;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import com.techeazy.notification.persistence.NotificationRequestRepository;
import com.techeazy.notification.port.RateLimiter.Decision;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Processes one message id delivered by the broker. Safe under redelivery: the atomic claim
 * ensures at most one worker sends a given message at a time, and terminal messages are skipped.
 */
@Service
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

    public sealed interface Outcome {
        /** Nothing more to do; acknowledge the broker message. */
        record Done() implements Outcome {}
        /** Rate limit hit; the caller should wait and call again. */
        record RateLimited(long waitMillis) implements Outcome {}
        /** Transient failure; redeliver after the delay. */
        record Retry(Duration delay) implements Outcome {}
        /** Every provider of the channel has an open circuit; hold the message, no attempt was spent. */
        record Unavailable(long waitMillis) implements Outcome {}
    }

    static final long UNAVAILABLE_POLL_MS = 2000;

    /** Acceptance-to-sent time per channel; see {@link #recordDeliveryLatency}. */
    public static final String DELIVERY_LATENCY = "notification.delivery.latency";
    /**
     * Every failure or retry of a delivery, by channel and error code (ADR-031), so a rise in one cause, such as
     * providers rejecting recipients on one channel, is visible and alerted on its own.
     */
    public static final String DELIVERY_ERRORS = "notification.delivery.errors";
    private static final String CHANNEL_TAG = "channel";
    private static final Duration[] DELIVERY_LATENCY_THRESHOLDS = {
            Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(5)};

    private final NotificationMessageRepository messages;
    private final NotificationRequestRepository requests;
    private final RateLimitService rateLimits;
    private final ProviderRegistry providers;
    private final DispatcherProperties props;
    private final MeterRegistry metrics;
    private final MessageEventLog events;
    private final TransactionOperations transactions;
    private final Map<UUID, SentDelivery> unrecordedSends = new ConcurrentHashMap<>();

    /**
     * {@code events} and {@code transactions} record each outcome in the event log in the same transaction as the
     * status change it describes (ADR-035).
     */
    @SuppressWarnings("java:S107")
    public DispatchService(NotificationMessageRepository messages, NotificationRequestRepository requests,
                           RateLimitService rateLimits, ProviderRegistry providers,
                           DispatcherProperties props, MeterRegistry metrics, MessageEventLog events,
                           TransactionOperations transactions) {
        this.events = events;
        this.transactions = transactions;
        this.messages = messages;
        this.requests = requests;
        this.rateLimits = rateLimits;
        this.providers = providers;
        this.props = props;
        this.metrics = metrics;
    }

    public Outcome process(UUID messageId) {
        Optional<Outcome> alreadySent = recordPendingSend(messageId);
        if (alreadySent.isPresent()) {
            return alreadySent.get();
        }
        NotificationMessage m = messages.findById(messageId).orElse(null);
        if (m == null) {
            log.warn("Message {} not found; dropping broker message", messageId);
            return new Outcome.Done();
        }
        if (m.getStatus().isTerminal() || m.getStatus() == MessageStatus.PROCESSING) {
            return new Outcome.Done(); // duplicate delivery, or another worker owns it
        }

        // A one-time password past its validity is useless to its recipient: drop it, even during an outage.
        if (expired(m)) {
            return expire(m);
        }

        // Checked before the rate limit and the claim so an outage costs neither a token nor an attempt.
        if (!providers.isAvailable(m.getChannel())) {
            count(m.getChannel(), "circuit_open");
            return new Outcome.Unavailable(UNAVAILABLE_POLL_MS);
        }

        Decision d = rateLimits.checkDelivery(m.getClientId(), m.getChannel(), m.getCategory());
        if (!d.allowed()) {
            count(m.getChannel(), "rate_limited");
            return new Outcome.RateLimited(d.waitMillis());
        }

        if (messages.claim(messageId, MessageStatus.CLAIMABLE, Instant.now()) == 0) {
            return new Outcome.Done();
        }
        int attempt = m.getAttempts() + 1;

        SendResult result;
        try {
            result = providers.send(render(m));
        } catch (ProviderRegistry.ProvidersUnavailableException e) {
            // Circuit opened between the availability check and the send: undo the claim, do not count the attempt.
            messages.release(messageId, Instant.now());
            count(m.getChannel(), "circuit_open");
            return new Outcome.Unavailable(UNAVAILABLE_POLL_MS);
        } catch (MessageContentMissingException e) {
            return failPermanently(m, attempt, ErrorCode.MESSAGE_CONTENT_MISSING, e);
        } catch (TemplateRenderer.MissingVariableException e) {
            return failPermanently(m, attempt, ErrorCode.TEMPLATE_VARIABLE_MISSING, e);
        } catch (PermanentSendException e) {
            return failPermanently(m, attempt, ErrorCode.DELIVERY_REJECTED, e);
        } catch (RuntimeException e) {
            if (attempt >= props.getMaxAttempts()) {
                Instant failedAt = Instant.now();
                transactions.executeWithoutResult(status -> {
                    messages.markFailed(messageId, FailureKind.EXHAUSTED, ErrorCode.DELIVERY_ATTEMPTS_EXHAUSTED,
                            truncate("Gave up after " + attempt + " attempts: " + e.getMessage()), failedAt);
                    events.append(MessageEvent.of(m, MessageEventType.FAILED, failedAt).withAttempt(attempt)
                            .withError(ErrorCode.DELIVERY_ATTEMPTS_EXHAUSTED)
                            .withDetail("Gave up after " + attempt + " attempts"));
                });
                count(m.getChannel(), "failed_exhausted");
                countError(metrics, m.getChannel().name(), ErrorCode.DELIVERY_ATTEMPTS_EXHAUSTED);
                return new Outcome.Done();
            }
            Duration backoff = backoff(attempt);
            Instant failedAt = Instant.now();
            transactions.executeWithoutResult(status -> {
                messages.markFailedOrRetry(messageId, MessageStatus.RETRYING, ErrorCode.PROVIDER_TEMPORARILY_FAILING,
                        truncate(e.getMessage()), failedAt);
                events.append(MessageEvent.of(m, MessageEventType.ATTEMPT_FAILED, failedAt).withAttempt(attempt)
                        .withError(ErrorCode.PROVIDER_TEMPORARILY_FAILING)
                        .withDetail("Next attempt in " + backoff.toSeconds() + " s"));
            });
            count(m.getChannel(), "retry");
            countError(metrics, m.getChannel().name(), ErrorCode.PROVIDER_TEMPORARILY_FAILING);
            return new Outcome.Retry(backoff);
        }
        return recordSent(new SentDelivery(m, attempt, result.providerMessageId(), Instant.now()));
    }

    /**
     * Records a send the provider has already accepted. A failure here is never treated as a failed delivery: the
     * message is not retried (which would send it twice) but kept in {@link #unrecordedSends} until
     * {@link #recordUnrecordedSends()} manages to write it, and the broker message is acknowledged.
     */
    private Outcome recordSent(SentDelivery delivery) {
        NotificationMessage m = delivery.message();
        count(m.getChannel(), "sent");
        recordDeliveryLatency(m, delivery.sentAt());
        try {
            writeSent(delivery);
        } catch (RuntimeException e) {
            unrecordedSends.put(m.getId(), delivery);
            count(m.getChannel(), "sent_unrecorded");
            log.error("Message {} was accepted by the provider (id {}) but recording it failed; the record will be "
                    + "retried, the send will not", m.getId(), delivery.providerMessageId(), e);
        }
        return new Outcome.Done();
    }

    /**
     * Retries writing the sends that were accepted by a provider but could not be recorded, for example during a
     * database failover. Until a send is recorded, a redelivery of its message is acknowledged without sending.
     */
    @Scheduled(fixedDelayString = "${dispatcher.unrecorded-send-retry-ms:2000}")
    void recordUnrecordedSends() {
        for (SentDelivery delivery : List.copyOf(unrecordedSends.values())) {
            try {
                writeSent(delivery);
                unrecordedSends.remove(delivery.message().getId(), delivery);
                log.info("Recorded the earlier send of message {}", delivery.message().getId());
            } catch (RuntimeException e) {
                log.warn("Still cannot record the send of message {}: {}", delivery.message().getId(), e.toString());
                return;
            }
        }
    }

    int unrecordedSendCount() {
        return unrecordedSends.size();
    }

    private void writeSent(SentDelivery delivery) {
        NotificationMessage m = delivery.message();
        transactions.executeWithoutResult(status -> {
            messages.markSent(m.getId(), delivery.providerMessageId(), delivery.sentAt());
            events.append(MessageEvent.of(m, MessageEventType.SENT, delivery.sentAt()).withAttempt(delivery.attempt())
                    .withProviderMessageId(delivery.providerMessageId()));
        });
    }

    private Optional<Outcome> recordPendingSend(UUID messageId) {
        SentDelivery pending = unrecordedSends.get(messageId);
        if (pending == null) {
            return Optional.empty();
        }
        try {
            writeSent(pending);
            unrecordedSends.remove(messageId, pending);
        } catch (RuntimeException e) {
            log.warn("Message {} was already sent; its record is still pending: {}", messageId, e.toString());
        }
        return Optional.of(new Outcome.Done());
    }

    /** A send the provider accepted, kept until it is recorded. */
    private record SentDelivery(NotificationMessage message, int attempt, String providerMessageId, Instant sentAt) {
    }

    private Outbound render(NotificationMessage m) {
        NotificationRequest req = requests.findById(m.getRequestId())
                .orElseThrow(() -> new MessageContentMissingException("Request " + m.getRequestId() + " not found"));
        // The request holds a snapshot of the content taken when it was accepted (from a template or inline), so
        // editing or deleting a template never changes or breaks a request that is already in flight.
        if (req.getBody() == null) {
            throw new MessageContentMissingException("Request " + req.getId() + " has no content");
        }
        Map<String, String> vars = new HashMap<>(m.getVariables());
        vars.put(TemplateRenderer.RECIPIENT, m.getRecipient());
        return new Outbound(m.getId(), m.getChannel(), m.getRecipient(),
                TemplateRenderer.render(req.getSubject(), vars), TemplateRenderer.render(req.getBody(), vars),
                req.getSenderEmail(), req.getSenderName(), TemplateRenderer.renderHtml(req.getBody(), vars));
    }

    private static boolean expired(NotificationMessage m) {
        return m.getExpiresAt() != null && !Instant.now().isBefore(m.getExpiresAt());
    }

    /** Claims the message first, so a worker sending it right now is never overruled. */
    private Outcome expire(NotificationMessage m) {
        if (messages.claim(m.getId(), MessageStatus.CLAIMABLE, Instant.now()) == 0) {
            return new Outcome.Done();
        }
        Instant expiredAt = Instant.now();
        transactions.executeWithoutResult(status -> {
            messages.markFailed(m.getId(), FailureKind.EXPIRED, ErrorCode.OTP_EXPIRED,
                    "The one-time password expired at " + m.getExpiresAt() + " before it could be sent", expiredAt);
            events.append(MessageEvent.of(m, MessageEventType.EXPIRED, expiredAt).withError(ErrorCode.OTP_EXPIRED)
                    .withDetail("Valid until " + m.getExpiresAt()));
        });
        count(m.getChannel(), "failed_expired");
        countError(metrics, m.getChannel().name(), ErrorCode.OTP_EXPIRED);
        return new Outcome.Done();
    }

    private Outcome failPermanently(NotificationMessage m, int attempt, ErrorCode errorCode, RuntimeException cause) {
        Instant failedAt = Instant.now();
        transactions.executeWithoutResult(status -> {
            messages.markFailed(m.getId(), FailureKind.PERMANENT, errorCode, truncate(cause.getMessage()), failedAt);
            events.append(MessageEvent.of(m, MessageEventType.FAILED, failedAt).withAttempt(attempt).withError(errorCode));
        });
        count(m.getChannel(), "failed_permanent");
        countError(metrics, m.getChannel().name(), errorCode);
        return new Outcome.Done();
    }

    /** The request behind a message has no content to render, typically because its personal data was erased. */
    static final class MessageContentMissingException extends RuntimeException {
        MessageContentMissingException(String message) {
            super(message);
        }
    }

    Duration backoff(int attempt) {
        long seconds = props.getBaseBackoffSeconds() * (1L << Math.min(attempt - 1, 20));
        return Duration.ofSeconds(Math.min(seconds, props.getMaxBackoffSeconds()));
    }

    /**
     * Time from acceptance to a successful send, with buckets at the freshness thresholds, so the share of messages
     * delivered within each can be computed and alerted on (see ADR-024).
     */
    private void recordDeliveryLatency(NotificationMessage m, Instant sentAt) {
        if (m.getCreatedAt() == null) {
            return;
        }
        Timer.builder(DELIVERY_LATENCY)
                .description("Time from accepting a message to its provider confirming the send")
                .tag(CHANNEL_TAG, m.getChannel().name())
                .tag("category", m.getCategory().name())
                .serviceLevelObjectives(DELIVERY_LATENCY_THRESHOLDS)
                .register(metrics)
                .record(Duration.between(m.getCreatedAt(), sentAt));
    }

    /** Counts one delivery error under {@link #DELIVERY_ERRORS}, tagged with its code and numbered error id. */
    static void countError(MeterRegistry meters, String channel, ErrorCode errorCode) {
        meters.counter(DELIVERY_ERRORS, CHANNEL_TAG, channel, "code", errorCode.code(), "error_id", errorCode.errorId())
                .increment();
    }

    private void count(Channel channel, String outcome) {
        metrics.counter("notification.dispatch", CHANNEL_TAG, channel.name(), "outcome", outcome).increment();
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 990 ? s.substring(0, 990) : s;
    }
}
