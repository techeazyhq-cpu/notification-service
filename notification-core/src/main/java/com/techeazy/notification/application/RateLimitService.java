package com.techeazy.notification.application;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.techeazy.notification.config.NotificationProperties;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.RateLimitPolicy;
import com.techeazy.notification.domain.RateLimitScope;
import com.techeazy.notification.persistence.RateLimitPolicyRepository;
import com.techeazy.notification.port.RateLimiter;
import com.techeazy.notification.port.RateLimiter.Decision;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the applicable admin-managed policies and enforces them through the {@link RateLimiter}.
 * Policies are cached briefly so admin changes take effect within {@code policyCacheSeconds}.
 */
@Service
public class RateLimitService {

    private static final Object KEY = new Object();

    private final RateLimiter limiter;
    private final NotificationProperties props;
    private final LoadingCache<Object, List<RateLimitPolicy>> policies;

    public RateLimitService(RateLimiter limiter, RateLimitPolicyRepository repo, NotificationProperties props) {
        this.limiter = limiter;
        this.props = props;
        this.policies = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(props.getRateLimit().getPolicyCacheSeconds()))
                .build(k -> repo.findByEnabledTrue());
    }

    /** One token per API call. Falls back to the configured default when the client has no policy. */
    public Decision checkClientApi(UUID clientId) {
        Optional<RateLimitPolicy> p = find(RateLimitScope.CLIENT_API, clientId, null);
        double rate = p.map(x -> x.getRatePerSecond().doubleValue()).orElse(props.getRateLimit().getDefaultClientApiRate());
        int burst = p.map(RateLimitPolicy::getBurst).orElse(props.getRateLimit().getDefaultClientApiBurst());
        return limiter.tryAcquire("api:" + clientId, rate, burst);
    }

    /** One token per message delivery: the client's own quota first, then the platform-wide channel cap. */
    public Decision checkDelivery(UUID clientId, Channel channel) {
        Decision worst = Decision.ALLOWED;
        Optional<RateLimitPolicy> client = find(RateLimitScope.CLIENT_CHANNEL, clientId, channel);
        if (client.isPresent()) {
            worst = merge(worst, limiter.tryAcquire("client:" + clientId + ":" + channel,
                    client.get().getRatePerSecond().doubleValue(), client.get().getBurst()));
        }
        if (!worst.allowed()) return worst; // do not spend a global token for a message we will not send now
        Optional<RateLimitPolicy> global = find(RateLimitScope.GLOBAL_CHANNEL, null, channel);
        if (global.isPresent()) {
            worst = merge(worst, limiter.tryAcquire("global:" + channel,
                    global.get().getRatePerSecond().doubleValue(), global.get().getBurst()));
        }
        return worst;
    }

    private static Decision merge(Decision a, Decision b) {
        if (!a.allowed()) return a;
        return b;
    }

    private Optional<RateLimitPolicy> find(RateLimitScope scope, UUID clientId, Channel channel) {
        return policies.get(KEY).stream()
                .filter(p -> p.getScope() == scope)
                .filter(p -> java.util.Objects.equals(p.getClientId(), clientId))
                .filter(p -> p.getChannel() == null || p.getChannel() == channel)
                .findFirst();
    }
}
