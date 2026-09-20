package com.techeazy.notification.dispatcher;

import com.techeazy.notification.application.RateLimitService;
import com.techeazy.notification.application.TemplateRenderer;
import com.techeazy.notification.dispatcher.provider.ChannelProvider.Outbound;
import com.techeazy.notification.dispatcher.provider.ChannelProvider.PermanentSendException;
import com.techeazy.notification.dispatcher.provider.ChannelProvider.SendResult;
import com.techeazy.notification.dispatcher.provider.ProviderRegistry;
import com.techeazy.notification.domain.*;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import com.techeazy.notification.persistence.NotificationRequestRepository;
import com.techeazy.notification.port.RateLimiter.Decision;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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

    private final NotificationMessageRepository messages;
    private final NotificationRequestRepository requests;
    private final RateLimitService rateLimits;
    private final ProviderRegistry providers;
    private final DispatcherProperties props;
    private final MeterRegistry metrics;

    public DispatchService(NotificationMessageRepository messages, NotificationRequestRepository requests,
                           RateLimitService rateLimits, ProviderRegistry providers,
                           DispatcherProperties props, MeterRegistry metrics) {
        this.messages = messages;
        this.requests = requests;
        this.rateLimits = rateLimits;
        this.providers = providers;
        this.props = props;
        this.metrics = metrics;
    }

    public Outcome process(UUID messageId) {
        NotificationMessage m = messages.findById(messageId).orElse(null);
        if (m == null) {
            log.warn("Message {} not found; dropping broker message", messageId);
            return new Outcome.Done();
        }
        if (m.getStatus().isTerminal() || m.getStatus() == MessageStatus.PROCESSING) {
            return new Outcome.Done(); // duplicate delivery, or another worker owns it
        }

        // Checked before the rate limit and the claim so an outage costs neither a token nor an attempt.
        if (!providers.isAvailable(m.getChannel())) {
            count(m.getChannel(), "circuit_open");
            return new Outcome.Unavailable(UNAVAILABLE_POLL_MS);
        }

        Decision d = rateLimits.checkDelivery(m.getClientId(), m.getChannel());
        if (!d.allowed()) {
            count(m.getChannel(), "rate_limited");
            return new Outcome.RateLimited(d.waitMillis());
        }

        if (messages.claim(messageId, MessageStatus.CLAIMABLE, Instant.now()) == 0) {
            return new Outcome.Done();
        }
        int attempt = m.getAttempts() + 1;

        try {
            Outbound outbound = render(m);
            SendResult result = providers.send(outbound);
            messages.markSent(messageId, result.providerMessageId(), Instant.now());
            count(m.getChannel(), "sent");
            return new Outcome.Done();
        } catch (ProviderRegistry.ProvidersUnavailableException e) {
            // Circuit opened between the availability check and the send: undo the claim, do not count the attempt.
            messages.release(messageId, Instant.now());
            count(m.getChannel(), "circuit_open");
            return new Outcome.Unavailable(UNAVAILABLE_POLL_MS);
        } catch (PermanentSendException | TemplateRenderer.MissingVariableException e) {
            messages.markFailedOrRetry(messageId, MessageStatus.FAILED, truncate(e.getMessage()), Instant.now());
            count(m.getChannel(), "failed_permanent");
            return new Outcome.Done();
        } catch (RuntimeException e) {
            if (attempt >= props.getMaxAttempts()) {
                messages.markFailedOrRetry(messageId, MessageStatus.FAILED,
                        truncate("Gave up after " + attempt + " attempts: " + e.getMessage()), Instant.now());
                count(m.getChannel(), "failed_exhausted");
                return new Outcome.Done();
            }
            messages.markFailedOrRetry(messageId, MessageStatus.RETRYING, truncate(e.getMessage()), Instant.now());
            count(m.getChannel(), "retry");
            return new Outcome.Retry(backoff(attempt));
        }
    }

    private Outbound render(NotificationMessage m) {
        NotificationRequest req = requests.findById(m.getRequestId())
                .orElseThrow(() -> new PermanentSendException("Request " + m.getRequestId() + " not found"));
        // The request holds a snapshot of the content taken when it was accepted (from a template or inline), so
        // editing or deleting a template never changes or breaks a request that is already in flight.
        if (req.getBody() == null) throw new PermanentSendException("Request " + req.getId() + " has no content");
        Map<String, String> vars = new HashMap<>(m.getVariables());
        vars.put(TemplateRenderer.RECIPIENT, m.getRecipient());
        return new Outbound(m.getId(), m.getChannel(), m.getRecipient(),
                TemplateRenderer.render(req.getSubject(), vars), TemplateRenderer.render(req.getBody(), vars));
    }

    Duration backoff(int attempt) {
        long seconds = props.getBaseBackoffSeconds() * (1L << Math.min(attempt - 1, 20));
        return Duration.ofSeconds(Math.min(seconds, props.getMaxBackoffSeconds()));
    }

    private void count(Channel channel, String outcome) {
        metrics.counter("notification.dispatch", "channel", channel.name(), "outcome", outcome).increment();
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 990 ? s.substring(0, 990) : s;
    }
}
