package com.techeazy.notification.dispatcher.provider;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.techeazy.notification.dispatcher.provider.ChannelProvider.*;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.ProviderConfig;
import com.techeazy.notification.domain.ProviderType;
import com.techeazy.notification.persistence.ProviderConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Sends through the enabled providers of a channel in priority order, failing over to the next
 * one on transient errors. Admin edits to provider configs apply within the cache TTL.
 */
@Component
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    private final Map<ProviderType, ChannelProvider> providers = new EnumMap<>(ProviderType.class);
    private final LoadingCache<Channel, List<ProviderConfig>> configs;

    public ProviderRegistry(List<ChannelProvider> impls, ProviderConfigRepository repo) {
        impls.forEach(p -> providers.put(p.type(), p));
        this.configs = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(10))
                .build(repo::findByChannelAndEnabledTrueOrderByPriorityAsc);
    }

    public SendResult send(Outbound message) {
        List<ProviderConfig> candidates = configs.get(message.channel());
        if (candidates.isEmpty()) {
            throw new TransientSendException("No enabled provider configured for channel " + message.channel());
        }
        TransientSendException last = null;
        for (ProviderConfig cfg : candidates) {
            ChannelProvider provider = providers.get(cfg.getType());
            if (provider == null) {
                last = new TransientSendException("No implementation for provider type " + cfg.getType());
                continue;
            }
            try {
                return provider.send(cfg, message);
            } catch (TransientSendException e) {
                log.warn("Provider '{}' failed transiently for message {}: {}", cfg.getName(), message.messageId(), e.getMessage());
                last = e;
            }
        }
        throw last;
    }
}
