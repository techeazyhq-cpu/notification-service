package com.techeazy.notification.dispatcher.provider;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.techeazy.notification.dispatcher.DispatcherProperties;
import com.techeazy.notification.dispatcher.provider.ChannelProvider.*;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.ProviderConfig;
import com.techeazy.notification.domain.ProviderType;
import com.techeazy.notification.persistence.ProviderConfigRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Sends through the enabled providers of a channel in priority order. Each provider sits behind its own
 * circuit breaker: a provider that keeps failing transiently is skipped (fast) until it recovers, and traffic
 * fails over to the next provider. When every provider of a channel is open, {@link ProvidersUnavailableException}
 * is thrown so the caller can hold the message without spending one of its retry attempts.
 * State is per dispatcher instance. Admin edits to provider configs apply within the cache TTL and reset that
 * provider's breaker.
 */
@Component
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    /** Every enabled provider of the channel is currently rejecting calls (circuit open). Not the message's fault. */
    public static class ProvidersUnavailableException extends RuntimeException {
        public ProvidersUnavailableException(Channel channel) {
            super("All providers for channel " + channel + " are unavailable (circuit open)");
        }
    }

    public record BreakerStatus(String provider, String state, float failureRate, float slowCallRate,
                                int bufferedCalls, long notPermittedCalls) {}

    private final Map<ProviderType, ChannelProvider> providers = new EnumMap<>(ProviderType.class);
    private final LoadingCache<Channel, List<ProviderConfig>> configs;
    private final CircuitBreakerRegistry breakers;
    private final boolean breakerEnabled;
    private final Map<String, Instant> breakerVersions = new HashMap<>();

    public ProviderRegistry(List<ChannelProvider> impls, ProviderConfigRepository repo,
                            CircuitBreakerRegistry breakers, DispatcherProperties props) {
        impls.forEach(p -> providers.put(p.type(), p));
        this.breakers = breakers;
        this.breakerEnabled = props.getCircuitBreaker().isEnabled();
        this.configs = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(10))
                .build(repo::findByChannelAndEnabledTrueOrderByPriorityAsc);
    }

    /** False only when the channel has providers and all of their circuits are open. */
    public boolean isAvailable(Channel channel) {
        List<ProviderConfig> candidates = configs.get(channel);
        if (!breakerEnabled || candidates.isEmpty()) return true; // "no provider" is a config error, surfaced by send()
        return candidates.stream().anyMatch(c -> {
            CircuitBreaker.State s = breakerFor(c).getState();
            return s != CircuitBreaker.State.OPEN && s != CircuitBreaker.State.FORCED_OPEN;
        });
    }

    public SendResult send(Outbound message) {
        List<ProviderConfig> candidates = configs.get(message.channel());
        if (candidates.isEmpty()) {
            throw new TransientSendException("No enabled provider configured for channel " + message.channel());
        }
        TransientSendException last = null;
        int skipped = 0;
        for (ProviderConfig cfg : candidates) {
            ChannelProvider provider = providers.get(cfg.getType());
            if (provider == null) {
                last = new TransientSendException("No implementation for provider type " + cfg.getType());
                continue;
            }
            try {
                if (!breakerEnabled) return provider.send(cfg, message);
                return breakerFor(cfg).executeSupplier(() -> provider.send(cfg, message));
            } catch (CallNotPermittedException e) {
                skipped++;
            } catch (TransientSendException e) {
                log.warn("Provider '{}' failed transiently for message {}: {}", cfg.getName(), message.messageId(), e.getMessage());
                last = e;
            }
        }
        if (last != null) throw last;
        if (skipped > 0) throw new ProvidersUnavailableException(message.channel());
        throw new TransientSendException("No usable provider for channel " + message.channel());
    }

    public List<BreakerStatus> breakerStatus() {
        return breakers.getAllCircuitBreakers().stream()
                .sorted(Comparator.comparing(CircuitBreaker::getName))
                .map(cb -> new BreakerStatus(cb.getName(), cb.getState().name(), cb.getMetrics().getFailureRate(),
                        cb.getMetrics().getSlowCallRate(), cb.getMetrics().getNumberOfBufferedCalls(),
                        cb.getMetrics().getNumberOfNotPermittedCalls()))
                .toList();
    }

    /** One breaker per provider name; editing the provider (new updatedAt) replaces it so a fixed config is retried at once. */
    private synchronized CircuitBreaker breakerFor(ProviderConfig cfg) {
        Instant previous = breakerVersions.put(cfg.getName(), cfg.getUpdatedAt());
        if (previous != null && !previous.equals(cfg.getUpdatedAt())) breakers.remove(cfg.getName());
        return breakers.circuitBreaker(cfg.getName());
    }
}
