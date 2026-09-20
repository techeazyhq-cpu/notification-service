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

import com.techeazy.notification.application.OutboxPublisher;
import com.techeazy.notification.billing.application.Admission;
import com.techeazy.notification.billing.application.AdmissionControl;
import com.techeazy.notification.billing.domain.HoldScope;
import com.techeazy.notification.billing.domain.InsufficientCreditException;
import com.techeazy.notification.billing.domain.Money;
import com.techeazy.notification.clientapi.IngestPersister.Recipient;
import com.techeazy.notification.clientapi.IngestService.SubmitCommand;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.NotificationRequest;
import com.techeazy.notification.domain.RequestKind;
import com.techeazy.notification.domain.Template;
import com.techeazy.notification.persistence.NotificationRequestRepository;
import com.techeazy.notification.persistence.TemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IngestServiceTest {

    TemplateRepository templates = mock(TemplateRepository.class);
    NotificationRequestRepository requests = mock(NotificationRequestRepository.class);
    IngestPersister persister = mock(IngestPersister.class);
    OutboxPublisher outbox = mock(OutboxPublisher.class);
    StatusQueryService status = mock(StatusQueryService.class);
    AdmissionControl admission = mock(AdmissionControl.class);
    SenderService senders = mock(SenderService.class);
    IngestService service =new IngestService(templates, requests, persister, outbox, status, admission, senders, 100);

    AuthenticatedClient me = new AuthenticatedClient(UUID.randomUUID(), "acme", Set.of(Channel.SMS, Channel.EMAIL));

    @BeforeEach
    void setUp() {
        when(persister.persist(any(), any(), any())).thenReturn(List.of());
    }

    Template template(UUID owner, String name, String body) {
        Template t = new Template();
        t.setId(UUID.randomUUID());
        t.setClientId(owner);
        t.setName(name);
        t.setChannel(Channel.SMS);
        t.setBody(body);
        return t;
    }

    SubmitCommand byTemplate(String name, List<Recipient> recipients) {
        return new SubmitCommand(RequestKind.BULK, Channel.SMS, name, null, null, recipients, null, null);
    }

    static Recipient to(String number, Map<String, String> vars) {
        return new Recipient(number, vars);
    }

    private NotificationRequest persistedRequest() {
        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(persister).persist(captor.capture(), any(), any());
        return captor.getValue();
    }

    @Test
    void clientsOwnTemplateWinsOverASharedOneOfTheSameName() {
        Template own = template(me.id(), "otp", "own {{code}}");
        when(templates.findByClientIdAndName(me.id(), "otp")).thenReturn(Optional.of(own));
        when(templates.findByClientIdIsNullAndName("otp")).thenReturn(Optional.of(template(null, "otp", "shared {{code}}")));

        service.submit(me, byTemplate("otp", List.of(to("+14155550101", Map.of("code", "1")))));

        NotificationRequest r = persistedRequest();
        assertThat(r.getBody()).isEqualTo("own {{code}}");
        assertThat(r.getTemplateId()).isEqualTo(own.getId());
    }

    @Test
    void fallsBackToTheSharedTemplate() {
        Template shared = template(null, "welcome", "hello");
        when(templates.findByClientIdAndName(me.id(), "welcome")).thenReturn(Optional.empty());
        when(templates.findByClientIdIsNullAndName("welcome")).thenReturn(Optional.of(shared));

        service.submit(me, byTemplate("welcome", List.of(to("+14155550101", Map.of()))));

        assertThat(persistedRequest().getTemplateId()).isEqualTo(shared.getId());
    }

    @Test
    void requestSnapshotsTheContentSoLaterEditsCannotChangeIt() {
        Template own = template(me.id(), "otp", "v1 {{code}}");
        when(templates.findByClientIdAndName(me.id(), "otp")).thenReturn(Optional.of(own));

        service.submit(me, byTemplate("otp", List.of(to("+14155550101", Map.of("code", "1")))));
        NotificationRequest r = persistedRequest();
        own.setBody("v2 {{code}} EDITED");

        assertThat(r.getBody()).isEqualTo("v1 {{code}}");
    }

    @Test
    void inlineContentIsStoredWithoutATemplateReference() {
        service.submit(me, new SubmitCommand(RequestKind.SINGLE, Channel.SMS, null, null, "Plain text", List.of(to("+14155550101", Map.of())), null, null));

        NotificationRequest r = persistedRequest();
        assertThat(r.getTemplateId()).isNull();
        assertThat(r.getBody()).isEqualTo("Plain text");
    }

    @Test
    void rejectsUnknownTemplatesAndChannelMismatches() {
        when(templates.findByClientIdAndName(any(), any())).thenReturn(Optional.empty());
        when(templates.findByClientIdIsNullAndName(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.submit(me, byTemplate("nope", List.of(to("+14155550101", Map.of())))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));

        Template email = template(me.id(), "mail", "x");
        email.setChannel(Channel.EMAIL);
        when(templates.findByClientIdAndName(me.id(), "mail")).thenReturn(Optional.of(email));
        SubmitCommand mailCommand = byTemplate("mail", List.of(to("+14155550101", Map.of())));
        assertThatThrownBy(() -> service.submit(me, mailCommand))
                .isInstanceOf(ApiException.class).hasMessageContaining("EMAIL");
        verifyNoInteractions(persister);
    }

    @Test
    void missingVariablesRejectTheWholeRequestBeforeAnythingIsStored() {
        when(templates.findByClientIdAndName(me.id(), "otp")).thenReturn(Optional.of(template(me.id(), "otp", "Hi {{name}}, code {{code}}")));
        List<Recipient> recipients = List.of(
                to("+14155550101", Map.of("name", "Ann", "code", "1")),
                to("+14155550102", Map.of("name", "Bob")));

        assertThatThrownBy(() -> service.submit(me, byTemplate("otp", recipients)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("recipient #2 is missing code").doesNotContain("recipient #1");
                });
        verifyNoInteractions(persister, outbox);
    }

    @Test
    void theBuiltInRecipientVariableNeedsNoValue() {
        when(templates.findByClientIdAndName(me.id(), "hi")).thenReturn(Optional.of(template(me.id(), "hi", "Sent to {{recipient}}")));

        service.submit(me, byTemplate("hi", List.of(to("+14155550101", null))));

        verify(persister).persist(any(), any(), any());
    }

    @Test
    void inlineContentGetsTheSameVariableCheck() {
        SubmitCommand inline = new SubmitCommand(RequestKind.SINGLE, Channel.SMS, null, null,
                "Code {{code}}", List.of(to("+14155550101", Map.of())), null, null);
        assertThatThrownBy(() -> service.submit(me, inline))
                .isInstanceOf(ApiException.class).hasMessageContaining("missing code");
    }

    private void runAdmissionInsideThePersistStep() {
        when(persister.persist(any(), any(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(2).run();
            return List.of();
        });
    }

    @Test
    void billingAdmissionRunsInsideTheStoringStepForThisRequest() {
        runAdmissionInsideThePersistStep();
        SubmitCommand command = new SubmitCommand(RequestKind.BULK, Channel.SMS, null, null, "hi",
                List.of(to("+14155550101", Map.of()), to("+14155550102", Map.of())), null, null);

        service.submit(me, command);

        NotificationRequest request = persistedRequest();
        verify(admission).admit(new Admission(me.id(), Channel.SMS, 2, HoldScope.REQUEST, request.getId()));
    }

    @Test
    void aRefusedAdmissionStopsTheRequestBeforeAnythingIsPublished() {
        runAdmissionInsideThePersistStep();
        doThrow(new InsufficientCreditException(Money.of("1", "USD"), Money.of("5", "USD"))).when(admission).admit(any());
        SubmitCommand command = new SubmitCommand(RequestKind.SINGLE, Channel.SMS, null, null, "hi",
                List.of(to("+14155550101", Map.of())), null, null);

        assertThatThrownBy(() -> service.submit(me, command)).isInstanceOf(InsufficientCreditException.class);

        verifyNoInteractions(outbox);
    }

    @Test
    void aRepeatedIdempotencyKeyReturnsTheOriginalWithoutAdmittingOrChargingAgain() {
        NotificationRequest original = new NotificationRequest();
        original.setId(UUID.randomUUID());
        original.setKind(RequestKind.SINGLE);
        original.setTotal(1);
        original.setCreatedAt(java.time.Instant.now());
        when(requests.findByClientIdAndIdempotencyKey(me.id(), "key-1")).thenReturn(Optional.of(original));
        when(status.view(original)).thenReturn(new Dtos.RequestView(original.getId(), RequestKind.SINGLE, Channel.SMS,
                com.techeazy.notification.domain.RequestStatus.PROCESSING, 1, null, null, original.getCreatedAt()));
        SubmitCommand command = new SubmitCommand(RequestKind.SINGLE, Channel.SMS, null, null, "hi",
                List.of(to("+14155550101", Map.of())), null, "key-1");

        Dtos.SubmitResponse response = service.submit(me, command);

        assertThat(response.idempotentReplay()).isTrue();
        assertThat(response.requestId()).isEqualTo(original.getId());
        verifyNoInteractions(persister, admission, outbox);
    }

    @Test
    void channelsTheClientMayNotUseAreForbidden() {
        AuthenticatedClient smsOnly = new AuthenticatedClient(UUID.randomUUID(), "x", Set.of(Channel.SMS));

        assertThatThrownBy(() -> service.submit(smsOnly, new SubmitCommand(RequestKind.SINGLE, Channel.PUSH, null, null,
                "hi", List.of(to("token", Map.of())), null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void theResolvedSenderIsCopiedOntoTheRequest() {
        SenderAddress sender = new SenderAddress(UUID.randomUUID(), me.id(), "orders@acme.com", "Acme Orders",
                SenderAddress.Status.VERIFIED, true, java.time.Instant.now(), java.time.Instant.now(), null);
        when(senders.resolve(me, Channel.EMAIL, "orders@acme.com")).thenReturn(Optional.of(sender));
        var captured = org.mockito.ArgumentCaptor.forClass(NotificationRequest.class);
        when(persister.persist(captured.capture(), any(), any())).thenReturn(List.of());

        service.submit(me, new SubmitCommand(RequestKind.SINGLE, Channel.EMAIL, null, "Hi", "hello",
                List.of(to("bob@example.com", Map.of())), null, null, "orders@acme.com"));

        assertThat(captured.getValue().getSenderEmail()).isEqualTo("orders@acme.com");
        assertThat(captured.getValue().getSenderName()).isEqualTo("Acme Orders");
    }

    @Test
    void withoutASenderTheRequestKeepsThePlatformDefault() {
        var captured = org.mockito.ArgumentCaptor.forClass(NotificationRequest.class);
        when(persister.persist(captured.capture(), any(), any())).thenReturn(List.of());

        service.submit(me, new SubmitCommand(RequestKind.SINGLE, Channel.EMAIL, null, "Hi", "hello",
                List.of(to("bob@example.com", Map.of())), null, null));

        assertThat(captured.getValue().getSenderEmail()).isNull();
    }

    @Test
    void anUnverifiedSenderRefusesTheRequestBeforeAnythingIsStored() {
        when(senders.resolve(me, Channel.EMAIL, "spoof@other.com")).thenThrow(new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SENDER_NOT_VERIFIED", "no"));

        assertThatThrownBy(() -> service.submit(me, new SubmitCommand(RequestKind.SINGLE, Channel.EMAIL, null, "Hi", "hello",
                List.of(to("bob@example.com", Map.of())), null, null, "spoof@other.com")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("SENDER_NOT_VERIFIED"));

        verifyNoInteractions(persister, outbox, admission);
    }
}
