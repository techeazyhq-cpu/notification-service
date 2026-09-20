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

    @BeforeEach
    void setUp() {
        breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(4).minimumNumberOfCalls(4).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(200)).permittedNumberOfCallsInHalfOpenState(2)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(TransientSendException.class).ignoreExceptions(PermanentSendException.class)
                .build());
        primary.behaviour = () -> { throw new TransientSendException("gateway down"); };
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
    void recoversThroughHalfOpenWhenTheProviderComesBack() throws Exception {
        withProviders(config("sms-primary", ProviderType.HTTP_JSON, 10));
        failTimes(4);
        assertThat(registry.isAvailable(Channel.SMS)).isFalse();

        primary.behaviour = () -> new SendResult("recovered");
        long deadline = System.currentTimeMillis() + 3000;
        while (!registry.isAvailable(Channel.SMS) && System.currentTimeMillis() < deadline) Thread.sleep(25);
        assertThat(registry.isAvailable(Channel.SMS)).as("half-open after the wait").isTrue();

        assertThat(registry.send(message).providerMessageId()).isEqualTo("recovered");
        assertThat(registry.send(message).providerMessageId()).isEqualTo("recovered");
        assertThat(breakers.circuitBreaker("sms-primary").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void aFailedProbeReopensTheCircuit() throws Exception {
        withProviders(config("sms-primary", ProviderType.HTTP_JSON, 10));
        failTimes(4);

        long deadline = System.currentTimeMillis() + 3000;
        while (!registry.isAvailable(Channel.SMS) && System.currentTimeMillis() < deadline) Thread.sleep(25);

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
