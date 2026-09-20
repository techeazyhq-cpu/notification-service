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

package com.techeazy.notification.dispatcher.provider;

import com.techeazy.notification.dispatcher.DispatcherProperties;
import com.techeazy.notification.dispatcher.provider.ChannelProvider.*;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.ProviderConfig;
import com.techeazy.notification.domain.ProviderType;
import com.techeazy.notification.persistence.ProviderConfigRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderRegistryTest {

    /** Test double whose behaviour and call count the test controls. */
    static class Stub implements ChannelProvider {
        final ProviderType type;
        final AtomicInteger calls = new AtomicInteger();
        volatile Supplier<SendResult> behaviour = () -> new SendResult("ok");

        Stub(ProviderType type) { this.type = type; }

        @Override public ProviderType type() { return type; }

        @Override public SendResult send(ProviderConfig config, Outbound message) {
            calls.incrementAndGet();
            return behaviour.get();
        }
    }

    Stub primary = new Stub(ProviderType.HTTP_JSON);
    Stub backup = new Stub(ProviderType.SMTP);
    ProviderConfigRepository repo = mock(ProviderConfigRepository.class);
    CircuitBreakerRegistry breakers;
    ProviderRegistry registry;
    Outbound message = new Outbound(UUID.randomUUID(), Channel.SMS, "+14155550123", null, "hi");

    static ProviderConfig config(String name, ProviderType type, int priority) {
        ProviderConfig c = new ProviderConfig();
        c.setId(UUID.randomUUID());
        c.setName(name);
        c.setType(type);
        c.setChannel(Channel.SMS);
        c.setPriority(priority);
        c.setEnabled(true);
        c.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return c;
    }

    void withProviders(ProviderConfig... configs) {
        when(repo.findByChannelAndEnabledTrueOrderByPriorityAsc(Channel.SMS)).thenReturn(List.of(configs));
        registry = new ProviderRegistry(List.of(primary, backup), repo, breakers, new DispatcherProperties());
    }

    /**
     * Most tests assert the OPEN state, so the open period is far longer than any test can take (a short one made them
     * flaky when the machine was busy). Only the recovery tests opt in to a short wait via {@link #shortOpenWait()}.
     */
    @BeforeEach
    void setUp() {
        breakers = registry(Duration.ofMinutes(5));
        primary.behaviour = () -> { throw new TransientSendException("gateway down"); };
    }

    private static CircuitBreakerRegistry registry(Duration openWait) {
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(4).minimumNumberOfCalls(4).failureRateThreshold(50)
                .waitDurationInOpenState(openWait).permittedNumberOfCallsInHalfOpenState(2)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(TransientSendException.class).ignoreExceptions(PermanentSendException.class)
                .build());
    }

    private void shortOpenWait() {
        breakers = registry(Duration.ofMillis(200));
    }

    private void failTimes(int n) {
        for (int i = 0; i < n; i++) {
            assertThatThrownBy(() -> registry.send(message)).isInstanceOf(TransientSendException.class);
        }
    }

    @Test
    void opensAfterRepeatedTransientFailuresAndStopsCallingTheProvider() {
        withProviders(config("sms-primary", ProviderType.HTTP_JSON, 10));

        failTimes(4);
        assertThat(registry.isAvailable(Channel.SMS)).isFalse();

        int before = primary.calls.get();
        assertThatThrownBy(() -> registry.send(message)).isInstanceOf(ProviderRegistry.ProvidersUnavailableException.class);
        assertThat(primary.calls.get()).isEqualTo(before); // rejected without touching the provider
    }

    @Test
    void failsOverToTheBackupProviderAndKeepsChannelAvailable() {
        withProviders(config("sms-primary", ProviderType.HTTP_JSON, 10), config("sms-backup", ProviderType.SMTP, 20));

        for (int i = 0; i < 6; i++) assertThat(registry.send(message).providerMessageId()).isEqualTo("ok");

        assertThat(registry.breakerStatus()).filteredOn(s -> s.provider().equals("sms-primary"))
                .singleElement().satisfies(s -> assertThat(s.state()).isEqualTo("OPEN"));
        assertThat(registry.isAvailable(Channel.SMS)).isTrue(); // backup still closed
        assertThat(primary.calls.get()).isEqualTo(4);           // later sends skipped the open primary
        assertThat(backup.calls.get()).isEqualTo(6);
    }

    @Test
    void permanentFailuresNeverOpenTheCircuit() {
        primary.behaviour = () -> { throw new PermanentSendException("invalid recipient"); };
        withProviders(config("sms-primary", ProviderType.HTTP_JSON, 10));

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> registry.send(message)).isInstanceOf(PermanentSendException.class);
        }
        assertThat(registry.isAvailable(Channel.SMS)).isTrue();
    }

    @Test
    void recoversThroughHalfOpenWhenTheProviderComesBack() {
        shortOpenWait();
        withProviders(config("sms-primary", ProviderType.HTTP_JSON, 10));
        failTimes(4);
        assertThat(registry.isAvailable(Channel.SMS)).isFalse();

        primary.behaviour = () -> new SendResult("recovered");
        await().atMost(Duration.ofSeconds(3)).until(() -> registry.isAvailable(Channel.SMS)); // half-open after the wait

        assertThat(registry.send(message).providerMessageId()).isEqualTo("recovered");
        assertThat(registry.send(message).providerMessageId()).isEqualTo("recovered");
        assertThat(breakers.circuitBreaker("sms-primary").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void aFailedProbeReopensTheCircuit() {
        shortOpenWait();
        withProviders(config("sms-primary", ProviderType.HTTP_JSON, 10));
        failTimes(4);

        await().atMost(Duration.ofSeconds(3)).until(() -> registry.isAvailable(Channel.SMS));

        failTimes(2); // both half-open probes fail; provider is still down
        assertThat(registry.isAvailable(Channel.SMS)).isFalse();
    }

    @Test
    void editingAProviderResetsItsBreaker() {
        ProviderConfig cfg = config("sms-primary", ProviderType.HTTP_JSON, 10);
        withProviders(cfg);
        failTimes(4);
        assertThat(registry.isAvailable(Channel.SMS)).isFalse();

        cfg.setUpdatedAt(Instant.parse("2026-02-01T00:00:00Z")); // admin fixed the URL
        assertThat(registry.isAvailable(Channel.SMS)).isTrue();
    }

    @Test
    void disabledBreakerNeverRejectsCalls() {
        DispatcherProperties props = new DispatcherProperties();
        props.getCircuitBreaker().setEnabled(false);
        when(repo.findByChannelAndEnabledTrueOrderByPriorityAsc(Channel.SMS))
                .thenReturn(List.of(config("sms-primary", ProviderType.HTTP_JSON, 10)));
        registry = new ProviderRegistry(List.of(primary, backup), repo, breakers, props);

        failTimes(10);
        assertThat(primary.calls.get()).isEqualTo(10);
        assertThat(registry.isAvailable(Channel.SMS)).isTrue();
    }
}
