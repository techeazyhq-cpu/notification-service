package com.techeazy.notification.dispatcher;

import com.techeazy.notification.application.RateLimitService;
import com.techeazy.notification.dispatcher.DispatchService.Outcome;
import com.techeazy.notification.dispatcher.provider.ChannelProvider.PermanentSendException;
import com.techeazy.notification.dispatcher.provider.ChannelProvider.SendResult;
import com.techeazy.notification.dispatcher.provider.ChannelProvider.TransientSendException;
import com.techeazy.notification.dispatcher.provider.ProviderRegistry;
import com.techeazy.notification.domain.*;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import com.techeazy.notification.persistence.NotificationRequestRepository;
import com.techeazy.notification.port.RateLimiter.Decision;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DispatchServiceTest {

    NotificationMessageRepository messages = mock(NotificationMessageRepository.class);
    NotificationRequestRepository requests = mock(NotificationRequestRepository.class);
    RateLimitService rateLimits = mock(RateLimitService.class);
    ProviderRegistry providers = mock(ProviderRegistry.class);
    DispatcherProperties props = new DispatcherProperties();
    DispatchService service;

    UUID id = UUID.randomUUID();
    NotificationMessage message;

    @BeforeEach
    void setUp() {
        props.setMaxAttempts(3);
        props.setBaseBackoffSeconds(5);
        service = new DispatchService(messages, requests, rateLimits, providers, props, new SimpleMeterRegistry());

        message = new NotificationMessage();
        message.setId(id);
        message.setRequestId(UUID.randomUUID());
        message.setClientId(UUID.randomUUID());
        message.setChannel(Channel.SMS);
        message.setRecipient("+14155550123");
        message.setVariables(Map.of("name", "Ann"));
        message.setStatus(MessageStatus.QUEUED);
        when(messages.findById(id)).thenReturn(Optional.of(message));

        NotificationRequest req = new NotificationRequest();
        req.setId(message.getRequestId());
        req.setBody("Hi {{name}}");
        when(requests.findById(message.getRequestId())).thenReturn(Optional.of(req));

        when(rateLimits.checkDelivery(any(), any())).thenReturn(Decision.GRANTED);
        when(messages.claim(eq(id), any(), any())).thenReturn(1);
        when(providers.isAvailable(any())).thenReturn(true);
    }

    @Test
    void openCircuitHoldsTheMessageWithoutSpendingATokenOrAnAttempt() {
        when(providers.isAvailable(Channel.SMS)).thenReturn(false);

        assertThat(service.process(id)).isEqualTo(new Outcome.Unavailable(DispatchService.UNAVAILABLE_POLL_MS));

        verifyNoInteractions(rateLimits);
        verify(messages, never()).claim(any(), any(), any());
        verify(providers, never()).send(any());
    }

    @Test
    void circuitOpeningMidSendReleasesTheClaimInsteadOfCountingAnAttempt() {
        when(providers.send(any())).thenThrow(new ProviderRegistry.ProvidersUnavailableException(Channel.SMS));

        assertThat(service.process(id)).isInstanceOf(Outcome.Unavailable.class);

        verify(messages).release(eq(id), any());
        verify(messages, never()).markFailedOrRetry(any(), any(), any(), any());
    }

    @Test
    void sendsAndMarksSent() {
        when(providers.send(any())).thenReturn(new SendResult("prov-1"));

        assertThat(service.process(id)).isInstanceOf(Outcome.Done.class);

        verify(providers).send(argThat(o -> o.body().equals("Hi Ann") && o.recipient().equals("+14155550123")));
        verify(messages).markSent(eq(id), eq("prov-1"), any());
    }

    @Test
    void terminalMessagesAreSkippedWithoutSending() {
        message.setStatus(MessageStatus.SENT);

        assertThat(service.process(id)).isInstanceOf(Outcome.Done.class);

        verify(providers, never()).send(any());
        verify(messages, never()).claim(any(), any(), any());
    }

    @Test
    void lostClaimMeansAnotherWorkerOwnsTheMessage() {
        when(messages.claim(eq(id), any(), any())).thenReturn(0);

        assertThat(service.process(id)).isInstanceOf(Outcome.Done.class);

        verify(providers, never()).send(any());
    }

    @Test
    void rateLimitedMessagesAreNotClaimed() {
        when(rateLimits.checkDelivery(any(), any())).thenReturn(new Decision(false, 250));

        assertThat(service.process(id)).isEqualTo(new Outcome.RateLimited(250));

        verify(messages, never()).claim(any(), any(), any());
        verify(providers, never()).send(any());
    }

    @Test
    void transientFailureSchedulesRetryWithExponentialBackoff() {
        when(providers.send(any())).thenThrow(new TransientSendException("timeout"));

        assertThat(service.process(id)).isEqualTo(new Outcome.Retry(Duration.ofSeconds(5)));
        verify(messages).markFailedOrRetry(eq(id), eq(MessageStatus.RETRYING), contains("timeout"), any());

        message.setAttempts(1);
        assertThat(service.process(id)).isEqualTo(new Outcome.Retry(Duration.ofSeconds(10)));
    }

    @Test
    void transientFailureOnLastAttemptMarksFailed() {
        message.setAttempts(2); // this is attempt 3 of 3
        when(providers.send(any())).thenThrow(new TransientSendException("still down"));

        assertThat(service.process(id)).isInstanceOf(Outcome.Done.class);

        verify(messages).markFailedOrRetry(eq(id), eq(MessageStatus.FAILED), contains("Gave up after 3"), any());
    }

    @Test
    void permanentFailureFailsImmediately() {
        when(providers.send(any())).thenThrow(new PermanentSendException("invalid recipient"));

        assertThat(service.process(id)).isInstanceOf(Outcome.Done.class);

        verify(messages).markFailedOrRetry(eq(id), eq(MessageStatus.FAILED), contains("invalid recipient"), any());
    }

    @Test
    void missingTemplateVariableFailsPermanentlyWithoutCallingProvider() {
        message.setVariables(Map.of()); // "name" is missing

        assertThat(service.process(id)).isInstanceOf(Outcome.Done.class);

        verify(providers, never()).send(any());
        verify(messages).markFailedOrRetry(eq(id), eq(MessageStatus.FAILED), contains("name"), any());
    }
}
