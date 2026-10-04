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

import com.techeazy.notification.application.RateLimitService;
import com.techeazy.notification.dispatcher.DispatchService.Outcome;
import com.techeazy.notification.dispatcher.provider.ChannelProvider.PermanentSendException;
import com.techeazy.notification.dispatcher.provider.ChannelProvider.SendResult;
import com.techeazy.notification.dispatcher.provider.ChannelProvider.TransientSendException;
import com.techeazy.notification.dispatcher.provider.ProviderRegistry;
import com.techeazy.notification.domain.*;
import com.techeazy.notification.domain.MessageCategory;
import com.techeazy.notification.domain.MessageEvent;
import com.techeazy.notification.domain.MessageEventType;
import com.techeazy.notification.error.ErrorCode;
import com.techeazy.notification.persistence.MessageEventLog;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import com.techeazy.notification.persistence.NotificationRequestRepository;
import com.techeazy.notification.port.RateLimiter.Decision;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DispatchServiceTest {

    NotificationMessageRepository messages = mock(NotificationMessageRepository.class);
    NotificationRequestRepository requests = mock(NotificationRequestRepository.class);
    RateLimitService rateLimits = mock(RateLimitService.class);
    ProviderRegistry providers = mock(ProviderRegistry.class);
    DispatcherProperties props = new DispatcherProperties();
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    MessageEventLog events = mock(MessageEventLog.class);
    DispatchService service;

    UUID id = UUID.randomUUID();
    NotificationMessage message;

    @BeforeEach
    void setUp() {
        props.setMaxAttempts(3);
        props.setBaseBackoffSeconds(5);
        service = new DispatchService(messages, requests, rateLimits, providers, props, meters, events,
                TransactionOperations.withoutTransaction());

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

        when(rateLimits.checkDelivery(any(), any(), any())).thenReturn(Decision.GRANTED);
        when(messages.claim(eq(id), any(), any())).thenReturn(1);
        when(providers.isAvailable(any())).thenReturn(true);
    }

    @Test
    void anExpiredOneTimePasswordFailsAsExpiredWithoutATokenOrAProviderCall() {
        message.setCategory(MessageCategory.OTP);
        message.setExpiresAt(Instant.now().minusSeconds(1));

        assertThat(service.process(id)).isInstanceOf(Outcome.Done.class);

        verify(messages).markFailed(eq(id), eq(FailureKind.EXPIRED), eq(ErrorCode.OTP_EXPIRED),
                contains("expired"), any());
        assertThat(errors("SMS", ErrorCode.OTP_EXPIRED)).isEqualTo(1.0);
        assertThat(loggedEvent()).satisfies(event -> {
            assertThat(event.type()).isEqualTo(MessageEventType.EXPIRED);
            assertThat(event.errorCode()).isEqualTo(ErrorCode.OTP_EXPIRED);
        });
        verifyNoInteractions(rateLimits);
        verify(providers, never()).send(any());
    }

    @Test
    void anExpiredOneTimePasswordIsDroppedEvenWhileEveryProviderIsDown() {
        message.setCategory(MessageCategory.OTP);
        message.setExpiresAt(Instant.now().minusSeconds(1));
        when(providers.isAvailable(Channel.SMS)).thenReturn(false);

        assertThat(service.process(id)).isInstanceOf(Outcome.Done.class);

        verify(messages).markFailed(eq(id), eq(FailureKind.EXPIRED), eq(ErrorCode.OTP_EXPIRED), any(), any());
    }

    @Test
    void aOneTimePasswordStillValidIsSentAndRateLimitedAsPriority() {
        message.setCategory(MessageCategory.OTP);
        message.setExpiresAt(Instant.now().plusSeconds(120));
        when(providers.send(any())).thenReturn(new SendResult("prov-otp"));

        assertThat(service.process(id)).isInstanceOf(Outcome.Done.class);

        verify(rateLimits).checkDelivery(message.getClientId(), Channel.SMS, MessageCategory.OTP);
        verify(messages).markSent(eq(id), eq("prov-otp"), any());
    }

    @Test
    void deliveryLatencyIsRecordedPerCategorySoOneTimePasswordsCanBeWatchedOnTheirOwn() {
        message.setCategory(MessageCategory.OTP);
        message.setCreatedAt(Instant.now().minusSeconds(3));
        when(providers.send(any())).thenReturn(new SendResult("prov-otp"));

        service.process(id);

        assertThat(meters.find(DispatchService.DELIVERY_LATENCY).tag("category", "OTP").timer()).isNotNull();
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
        verify(messages, never()).markFailedOrRetry(any(), any(), any(), any(), any());
    }

    @Test
    void theTimeFromAcceptanceToSendingIsMeasuredPerChannelAgainstTheFreshnessThresholds() {
        message.setCreatedAt(Instant.now().minusSeconds(42));
        when(providers.send(any())).thenReturn(new SendResult("prov-1"));

        service.process(id);

        Timer latency = meters.get(DispatchService.DELIVERY_LATENCY).tag("channel", "SMS").timer();
        assertThat(latency.count()).isEqualTo(1);
        assertThat(latency.totalTime(TimeUnit.SECONDS)).isBetween(42.0, 60.0);
        assertThat(latency.takeSnapshot().histogramCounts())
                .extracting(bucket -> bucket.bucket(TimeUnit.SECONDS))
                .contains(10.0, 30.0, 60.0, 300.0);
    }

    @Test
    void aMessageThatIsNotSentIsNotMeasuredAsDelivered() {
        message.setCreatedAt(Instant.now().minusSeconds(42));
        when(providers.send(any())).thenThrow(new PermanentSendException("invalid number"));

        service.process(id);

        assertThat(meters.find(DispatchService.DELIVERY_LATENCY).timer()).isNull();
    }

    @Test
    void sendsAndMarksSent() {
        when(providers.send(any())).thenReturn(new SendResult("prov-1"));

        assertThat(service.process(id)).isInstanceOf(Outcome.Done.class);

        verify(providers).send(argThat(o -> o.body().equals("Hi Ann") && o.recipient().equals("+14155550123")));
        verify(messages).markSent(eq(id), eq("prov-1"), any());
    }

    @Test
    void theHtmlBodyEscapesVariablesWhileThePlainBodyKeepsThemAsWritten() {
        message.setVariables(Map.of("name", "<script>x</script>"));
        when(providers.send(any())).thenReturn(new SendResult("prov-1"));

        service.process(id);

        verify(providers).send(argThat(o -> o.body().equals("Hi <script>x</script>")
                && o.htmlBody().equals("Hi &lt;script&gt;x&lt;/script&gt;")));
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
        when(rateLimits.checkDelivery(any(), any(), any())).thenReturn(new Decision(false, 250));

        assertThat(service.process(id)).isEqualTo(new Outcome.RateLimited(250));

        verify(messages, never()).claim(any(), any(), any());
        verify(providers, never()).send(any());
    }

    @Test
    void transientFailureSchedulesRetryWithExponentialBackoff() {
        when(providers.send(any())).thenThrow(new TransientSendException("timeout"));

        assertThat(service.process(id)).isEqualTo(new Outcome.Retry(Duration.ofSeconds(5)));
        verify(messages).markFailedOrRetry(eq(id), eq(MessageStatus.RETRYING),
                eq(ErrorCode.PROVIDER_TEMPORARILY_FAILING),
                contains("timeout"), any());
        assertThat(errors("SMS", ErrorCode.PROVIDER_TEMPORARILY_FAILING)).isEqualTo(1.0);
        assertThat(loggedEvent()).satisfies(event -> {
            assertThat(event.type()).isEqualTo(MessageEventType.ATTEMPT_FAILED);
            assertThat(event.attempt()).isEqualTo(1);
            assertThat(event.errorCode()).isEqualTo(ErrorCode.PROVIDER_TEMPORARILY_FAILING);
            assertThat(event.detail()).isEqualTo("Next attempt in 5 s");
        });

        message.setAttempts(1);
        assertThat(service.process(id)).isEqualTo(new Outcome.Retry(Duration.ofSeconds(10)));
    }

    @Test
    void transientFailureOnLastAttemptMarksFailed() {
        message.setAttempts(2); // this is attempt 3 of 3
        when(providers.send(any())).thenThrow(new TransientSendException("still down"));

        assertThat(service.process(id)).isInstanceOf(Outcome.Done.class);

        verify(messages).markFailed(eq(id), eq(FailureKind.EXHAUSTED), eq(ErrorCode.DELIVERY_ATTEMPTS_EXHAUSTED),
                contains("Gave up after 3"), any());
        assertThat(errors("SMS", ErrorCode.DELIVERY_ATTEMPTS_EXHAUSTED)).isEqualTo(1.0);
        assertThat(loggedEvent()).satisfies(event -> {
            assertThat(event.type()).isEqualTo(MessageEventType.FAILED);
            assertThat(event.attempt()).isEqualTo(3);
            assertThat(event.errorCode()).isEqualTo(ErrorCode.DELIVERY_ATTEMPTS_EXHAUSTED);
        });
    }

    @Test
    void permanentFailureFailsImmediately() {
        when(providers.send(any())).thenThrow(new PermanentSendException("invalid recipient"));

        assertThat(service.process(id)).isInstanceOf(Outcome.Done.class);

        verify(messages).markFailed(eq(id), eq(FailureKind.PERMANENT), eq(ErrorCode.DELIVERY_REJECTED),
                contains("invalid recipient"), any());
        assertThat(errors("SMS", ErrorCode.DELIVERY_REJECTED)).isEqualTo(1.0);
        assertThat(loggedEvent()).satisfies(event -> {
            assertThat(event.type()).isEqualTo(MessageEventType.FAILED);
            assertThat(event.errorCode()).isEqualTo(ErrorCode.DELIVERY_REJECTED);
            assertThat(event.detail()).as("provider wording can quote the recipient").isNull();
        });
        assertThat(meters.get(DispatchService.DELIVERY_ERRORS).tag("code", "DELIVERY_REJECTED").counter().getId()
                .getTag("error_id")).isEqualTo(ErrorCode.DELIVERY_REJECTED.errorId());
    }

    @Test
    void missingTemplateVariableFailsPermanentlyWithoutCallingProvider() {
        message.setVariables(Map.of()); // "name" is missing

        assertThat(service.process(id)).isInstanceOf(Outcome.Done.class);

        verify(providers, never()).send(any());
        verify(messages).markFailed(eq(id), eq(FailureKind.PERMANENT), eq(ErrorCode.TEMPLATE_VARIABLE_MISSING),
                contains("name"), any());
    }

    @Test
    void aRequestWhoseContentWasErasedFailsAsContentMissingWithoutCallingProvider() {
        NotificationRequest erased = new NotificationRequest();
        erased.setId(message.getRequestId());
        when(requests.findById(message.getRequestId())).thenReturn(Optional.of(erased));

        assertThat(service.process(id)).isInstanceOf(Outcome.Done.class);

        verify(providers, never()).send(any());
        verify(messages).markFailed(eq(id), eq(FailureKind.PERMANENT), eq(ErrorCode.MESSAGE_CONTENT_MISSING),
                contains("no content"), any());
        assertThat(errors("SMS", ErrorCode.MESSAGE_CONTENT_MISSING)).isEqualTo(1.0);
    }

    /** The log keeps no personal data: a sent message's event holds the attempt and provider id, nothing else. */
    @Test
    void aSentMessageIsLoggedWithItsAttemptAndProviderId() {
        when(providers.send(any())).thenReturn(new SendResult("prov-77"));

        service.process(id);

        assertThat(loggedEvent()).satisfies(event -> {
            assertThat(event.type()).isEqualTo(MessageEventType.SENT);
            assertThat(event.messageId()).isEqualTo(id);
            assertThat(event.clientId()).isEqualTo(message.getClientId());
            assertThat(event.attempt()).isEqualTo(1);
            assertThat(event.providerMessageId()).isEqualTo("prov-77");
            assertThat(event.errorCode()).isNull();
        });
    }

    @Test
    void aMessageHeldForAnOutageLogsNothing() {
        when(providers.isAvailable(Channel.SMS)).thenReturn(false);

        service.process(id);

        verify(events, never()).append(any());
    }

    @Test
    void aSentMessageCountsNoDeliveryError() {
        when(providers.send(any())).thenReturn(new SendResult("prov-1"));

        service.process(id);

        assertThat(meters.find(DispatchService.DELIVERY_ERRORS).counters()).isEmpty();
    }

    private MessageEvent loggedEvent() {
        ArgumentCaptor<MessageEvent> event = ArgumentCaptor.forClass(MessageEvent.class);
        verify(events).append(event.capture());
        return event.getValue();
    }

    private double errors(String channel, ErrorCode errorCode) {
        return meters.get(DispatchService.DELIVERY_ERRORS).tag("channel", channel).tag("code", errorCode.code())
                .counter().count();
    }
}
