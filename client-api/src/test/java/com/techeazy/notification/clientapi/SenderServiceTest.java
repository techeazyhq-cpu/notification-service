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

import com.techeazy.notification.application.ApiKeys;
import com.techeazy.notification.domain.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SenderServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-20T10:00:00Z");

    private final SenderRepository repository = mock(SenderRepository.class);
    private final VerificationMailer mailer = mock(VerificationMailer.class);
    private final AuthenticatedClient acme = new AuthenticatedClient(UUID.randomUUID(), "acme", Set.of(Channel.EMAIL, Channel.SMS));
    private SenderService service;

    @BeforeEach
    void setUp() {
        service = new SenderService(repository, mailer, Clock.fixed(T0, ZoneOffset.UTC), "https://api.example.com/", 3);
    }

    private SenderAddress sender(String email, SenderAddress.Status status, boolean isDefault, Instant sentAt) {
        return new SenderAddress(UUID.randomUUID(), acme.id(), email, null, status, isDefault, T0,
                status == SenderAddress.Status.VERIFIED ? T0 : null, sentAt);
    }

    @Test
    void addingStoresAPendingAddressAndMailsAConfirmationLinkWithASingleUseToken() {
        SenderAddress created = service.add(acme, "  Orders@Acme.COM ", " Acme <Orders> ");

        assertThat(created.email()).isEqualTo("orders@acme.com");
        assertThat(created.displayName()).isEqualTo("Acme Orders");
        assertThat(created.status()).isEqualTo(SenderAddress.Status.PENDING);
        var url = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mailer).send(eq("orders@acme.com"), eq("acme"), url.capture());
        assertThat(url.getValue()).startsWith("https://api.example.com/v1/senders/verify?token=");
        String token = url.getValue().substring(url.getValue().indexOf("token=") + 6);
        verify(repository).insert(any(), eq(ApiKeys.hash(token)), eq(T0.plus(Duration.ofHours(24))));
    }

    @Test
    void badAddressesDuplicatesAndTheLimitAreRefused() {
        assertThatThrownBy(() -> service.add(acme, "not-an-email", null)).isInstanceOf(ApiException.class).hasMessageContaining("email");

        when(repository.emailExists(acme.id(), "dup@acme.com")).thenReturn(true);
        assertThatThrownBy(() -> service.add(acme, "dup@acme.com", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("SENDER_EXISTS"));

        when(repository.countByClient(acme.id())).thenReturn(3);
        assertThatThrownBy(() -> service.add(acme, "new@acme.com", null)).hasMessageContaining("At most 3");
    }

    @Test
    void aClientWithoutTheEmailChannelCannotRegisterSenders() {
        AuthenticatedClient smsOnly = new AuthenticatedClient(UUID.randomUUID(), "sms", Set.of(Channel.SMS));

        assertThatThrownBy(() -> service.add(smsOnly, "a@b.com", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void ifTheConfirmationMailCannotBeSentTheAddressIsNotKept() {
        doThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_NOT_AVAILABLE", "x")).when(mailer).send(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> service.add(acme, "orders@acme.com", null)).isInstanceOf(ApiException.class);

        verify(repository).delete(eq(acme.id()), any());
    }

    @Test
    void resendIsLimitedToOncePerMinuteAndNotForVerifiedAddresses() {
        SenderAddress recent = sender("a@acme.com", SenderAddress.Status.PENDING, false, T0.minusSeconds(30));
        when(repository.find(acme.id(), recent.id())).thenReturn(Optional.of(recent));
        assertThatThrownBy(() -> service.resend(acme, recent.id()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        SenderAddress verified = sender("b@acme.com", SenderAddress.Status.VERIFIED, false, null);
        when(repository.find(acme.id(), verified.id())).thenReturn(Optional.of(verified));
        assertThatThrownBy(() -> service.resend(acme, verified.id())).hasMessageContaining("already verified");

        SenderAddress old = sender("c@acme.com", SenderAddress.Status.PENDING, false, T0.minusSeconds(120));
        when(repository.find(acme.id(), old.id())).thenReturn(Optional.of(old));
        service.resend(acme, old.id());
        verify(mailer).send(eq("c@acme.com"), eq("acme"), anyString());
        verify(repository).replaceToken(eq(old.id()), anyString(), eq(T0.plus(Duration.ofHours(24))), eq(T0));
    }

    @Test
    void verifyLooksTheTokenUpByItsHashAndIgnoresBlankTokens() {
        SenderAddress verified = sender("a@acme.com", SenderAddress.Status.VERIFIED, false, null);
        when(repository.verifyByToken(ApiKeys.hash("tok"), T0)).thenReturn(Optional.of(verified));

        assertThat(service.verify(" tok ")).contains(verified);
        assertThat(service.verify("other")).isEmpty();
        assertThat(service.verify(" ")).isEmpty();
        assertThat(service.verify(null)).isEmpty();
    }

    @Test
    void onlyAVerifiedAddressCanBeTheDefault() {
        SenderAddress pending = sender("a@acme.com", SenderAddress.Status.PENDING, false, T0);
        when(repository.find(acme.id(), pending.id())).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.makeDefault(acme, pending.id()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("SENDER_NOT_VERIFIED"));

        SenderAddress verified = sender("b@acme.com", SenderAddress.Status.VERIFIED, false, null);
        when(repository.find(acme.id(), verified.id())).thenReturn(Optional.of(verified));
        service.makeDefault(acme, verified.id());
        verify(repository).clearDefault(acme.id());
        verify(repository).markDefault(acme.id(), verified.id());
    }

    @Test
    void resolvePrefersTheRequestedAddressThenTheDefaultThenNothing() {
        SenderAddress requested = sender("orders@acme.com", SenderAddress.Status.VERIFIED, false, null);
        SenderAddress def = sender("hello@acme.com", SenderAddress.Status.VERIFIED, true, null);
        when(repository.findVerifiedByEmail(acme.id(), "orders@acme.com")).thenReturn(Optional.of(requested));
        when(repository.findVerifiedDefault(acme.id())).thenReturn(Optional.of(def));

        assertThat(service.resolve(acme, Channel.EMAIL, "Orders@Acme.com")).contains(requested);
        assertThat(service.resolve(acme, Channel.EMAIL, null)).contains(def);
        assertThat(service.resolve(acme, Channel.SMS, null)).isEmpty();
    }

    @Test
    void anUnverifiedOrForeignFromAddressIsRefusedAndFromIsEmailOnly() {
        assertThatThrownBy(() -> service.resolve(acme, Channel.EMAIL, "spoof@other.com"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("SENDER_NOT_VERIFIED");
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
        assertThatThrownBy(() -> service.resolve(acme, Channel.SMS, "a@acme.com")).hasMessageContaining("only supported for the EMAIL");
    }

    @Test
    void deletingAnUnknownAddressIsNotFound() {
        assertThatThrownBy(() -> service.delete(acme, UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
