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
package com.techeazy.notification.adminapi;

import com.techeazy.notification.billing.application.Admission;
import com.techeazy.notification.billing.application.AdmissionControl;
import com.techeazy.notification.billing.domain.HoldScope;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.FailureKind;
import com.techeazy.notification.domain.MessageCategory;
import com.techeazy.notification.domain.MessageStatus;
import com.techeazy.notification.domain.NotificationMessage;
import com.techeazy.notification.persistence.MessageEventLog;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageRetryTest {

    private final NotificationMessageRepository messages = mock(NotificationMessageRepository.class);
    private final AdmissionControl admission = mock(AdmissionControl.class);
    private final MessageEventLog events = mock(MessageEventLog.class);
    private final MessageRetry retry = new MessageRetry(messages, admission, events);

    @Test
    void anExpiredOneTimePasswordIsNotSentAgainBecauseItsCodeIsNoLongerValid() {
        NotificationMessage expired = failed(FailureKind.EXPIRED);
        expired.setCategory(MessageCategory.OTP);
        expired.setExpiresAt(Instant.now().minusSeconds(60));
        when(messages.findById(expired.getId())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> retry.requeue(expired.getId()))
                .isInstanceOfSatisfying(ResponseStatusException.class, refused -> {
                    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(refused.getReason()).contains("expired");
                });
        verify(messages, never()).requeueFailed(any(), any());
        verify(admission, never()).admit(any());
    }

    @Test
    void anOrdinaryFailedMessageIsRequeued() {
        NotificationMessage exhausted = failed(FailureKind.EXHAUSTED);
        when(messages.findById(exhausted.getId())).thenReturn(Optional.of(exhausted));
        when(messages.requeueFailed(eq(exhausted.getId()), any())).thenReturn(1);

        assertThat(retry.requeue(exhausted.getId())).isSameAs(exhausted);
        verify(admission).admit(new Admission(exhausted.getClientId(), Channel.SMS, 1, HoldScope.MESSAGE,
                exhausted.getId(), false));
    }

    /** A retried one-time password is charged at the tenant's OTP price again (ADR-034). */
    @Test
    void aRetriedOneTimePasswordIsAdmittedAsOne() {
        NotificationMessage otp = failed(FailureKind.EXHAUSTED);
        otp.setCategory(MessageCategory.OTP);
        otp.setExpiresAt(Instant.now().plusSeconds(120));
        when(messages.findById(otp.getId())).thenReturn(Optional.of(otp));
        when(messages.requeueFailed(eq(otp.getId()), any())).thenReturn(1);

        retry.requeue(otp.getId());

        verify(admission).admit(new Admission(otp.getClientId(), Channel.SMS, 1, HoldScope.MESSAGE, otp.getId(), true));
    }

    private static NotificationMessage failed(FailureKind kind) {
        NotificationMessage message = new NotificationMessage();
        message.setId(UUID.randomUUID());
        message.setClientId(UUID.randomUUID());
        message.setChannel(Channel.SMS);
        message.setRecipient("+14155550123");
        message.setStatus(MessageStatus.FAILED);
        message.setFailureKind(kind);
        return message;
    }
}
