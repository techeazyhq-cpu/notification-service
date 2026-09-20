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

package com.techeazy.notification.clientapi;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.Template;
import com.techeazy.notification.persistence.TemplateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Remembers the template a client's name resolves to for a few seconds, so sending does not query the database for
 * it on every request. Only hits are kept, so a template created a moment ago is found at once. Edits and deletes
 * made through this service take effect immediately here; changes made elsewhere (an administrator editing a shared
 * template, another instance) are seen after at most {@code client-api.template-cache-seconds}. Requests keep a
 * snapshot of their content, so a stale read can only affect requests accepted in that window.
 */
@Component
public class TemplateCache {

    /** The part of a template a request needs, detached from persistence. */
    public record TemplateContent(UUID id, Channel channel, String subject, String body) {}

    private record Key(UUID clientId, String name) {}

    private final TemplateRepository templates;
    private final Cache<Key, TemplateContent> cache;
    private final boolean enabled;

    public TemplateCache(TemplateRepository templates, @Value("${client-api.template-cache-seconds:5}") long ttlSeconds) {
        this.templates = templates;
        this.cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(Math.max(ttlSeconds, 1))).maximumSize(20_000).build();
        this.enabled = ttlSeconds > 0;
    }

    /** The client's own template wins over a shared one of the same name. */
    public Optional<TemplateContent> find(UUID clientId, String name) {
        if (!enabled) {
            return load(clientId, name);
        }
        Key key = new Key(clientId, name);
        TemplateContent cached = cache.getIfPresent(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<TemplateContent> loaded = load(clientId, name);
        loaded.ifPresent(content -> cache.put(key, content));
        return loaded;
    }

    /** Drops the client's entries once the surrounding transaction has committed, so nobody re-caches the old version in between. */
    public void invalidateAfterCommit(UUID clientId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate(clientId);
                }
            });
        } else {
            invalidate(clientId);
        }
    }

    public void invalidate(UUID clientId) {
        cache.asMap().keySet().removeIf(key -> key.clientId().equals(clientId));
    }

    private Optional<TemplateContent> load(UUID clientId, String name) {
        return templates.findByClientIdAndName(clientId, name)
                .or(() -> templates.findByClientIdIsNullAndName(name))
                .map(TemplateCache::content);
    }

    private static TemplateContent content(Template t) {
        return new TemplateContent(t.getId(), t.getChannel(), t.getSubject(), t.getBody());
    }
}
