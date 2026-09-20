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

import com.techeazy.notification.clientapi.Dtos.*;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.Template;
import com.techeazy.notification.persistence.TemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClientTemplateServiceTest {

    TemplateRepository repo = mock(TemplateRepository.class);
    ClientTemplateService service = new ClientTemplateService(repo, 2);
    AuthenticatedClient me = new AuthenticatedClient(UUID.randomUUID(), "acme", Set.of(Channel.EMAIL, Channel.SMS));

    @BeforeEach
    void setUp() {
        when(repo.saveAndFlush(any(Template.class))).thenAnswer(i -> i.getArgument(0));
    }

    static TemplateInput sms(String name, String body) {
        return new TemplateInput(name, Channel.SMS, null, body);
    }

    Template stored(UUID owner, String name, Channel channel) {
        Template t = new Template();
        t.setId(UUID.randomUUID());
        t.setClientId(owner);
        t.setName(name);
        t.setChannel(channel);
        t.setSubject(channel == Channel.EMAIL ? "Subject {{name}}" : null);
        t.setBody("Hi {{name}}, code {{code}} for {{recipient}}");
        t.setCreatedAt(Instant.now());
        t.setUpdatedAt(Instant.now());
        return t;
    }

    private static void assertRejected(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            if (code != null) assertThat(e.code()).isEqualTo(code);
        });
    }

    @Test
    void createsAnOwnedTemplateAndListsItsVariables() {
        TemplateView v = service.create(me, sms("otp-sms", "Code {{code}} for {{ recipient }} ({{name}})"));

        assertThat(v.scope()).isEqualTo(TemplateScope.OWNED);
        assertThat(v.readOnly()).isFalse();
        assertThat(v.variables()).containsExactly("code", "name"); // recipient is built in
        verify(repo).saveAndFlush(argThat(t -> me.id().equals(t.getClientId()) && t.getName().equals("otp-sms")));
    }

    @Test
    void refusesNamesTakenByASharedTemplateOrByYourself() {
        when(repo.findByClientIdIsNullAndName("welcome")).thenReturn(Optional.of(stored(null, "welcome", Channel.SMS)));
        when(repo.findByClientIdAndName(me.id(), "mine")).thenReturn(Optional.of(stored(me.id(), "mine", Channel.SMS)));

        assertRejected(() -> service.create(me, sms("welcome", "x")), HttpStatus.CONFLICT, "TEMPLATE_NAME_RESERVED");
        assertRejected(() -> service.create(me, sms("mine", "x")), HttpStatus.CONFLICT, "TEMPLATE_EXISTS");
    }

    @Test
    void enforcesThePerClientLimit() {
        when(repo.countByClientId(me.id())).thenReturn(2L);

        assertRejected(() -> service.create(me, sms("another", "x")), HttpStatus.CONFLICT, "TEMPLATE_LIMIT");
    }

    @Test
    void validatesNameChannelSubjectAndPlaceholders() {
        assertRejected(() -> service.create(me, sms("bad name!", "x")), HttpStatus.BAD_REQUEST, null);
        assertRejected(() -> service.create(me, new TemplateInput("wa", Channel.WHATSAPP, null, "x")), HttpStatus.FORBIDDEN, "CHANNEL_NOT_ALLOWED");
        assertRejected(() -> service.create(me, new TemplateInput("mail", Channel.EMAIL, " ", "x")), HttpStatus.BAD_REQUEST, null);
        assertRejected(() -> service.create(me, sms("typo", "Hi {{name}")), HttpStatus.BAD_REQUEST, null);
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void updatesOwnTemplateButNeverItsChannel() {
        Template mine = stored(me.id(), "otp", Channel.SMS);
        when(repo.findById(mine.getId())).thenReturn(Optional.of(mine));

        TemplateView v = service.update(me, mine.getId(), sms("otp", "New {{code}}"));

        assertThat(v.body()).isEqualTo("New {{code}}");
        assertRejected(() -> service.update(me, mine.getId(), new TemplateInput("otp", Channel.EMAIL, "s", "b")), HttpStatus.BAD_REQUEST, null);
    }

    @Test
    void sharedTemplatesAreReadOnlyAndOtherClientsTemplatesAreInvisible() {
        Template shared = stored(null, "welcome", Channel.SMS);
        Template theirs = stored(UUID.randomUUID(), "secret", Channel.SMS);
        when(repo.findById(shared.getId())).thenReturn(Optional.of(shared));
        when(repo.findById(theirs.getId())).thenReturn(Optional.of(theirs));

        assertThat(service.get(me, shared.getId()).readOnly()).isTrue();
        assertRejected(() -> service.update(me, shared.getId(), sms("welcome", "x")), HttpStatus.FORBIDDEN, "TEMPLATE_READ_ONLY");
        assertRejected(() -> service.delete(me, shared.getId()), HttpStatus.FORBIDDEN, "TEMPLATE_READ_ONLY");
        assertRejected(() -> service.get(me, theirs.getId()), HttpStatus.NOT_FOUND, null);
        assertRejected(() -> service.delete(me, theirs.getId()), HttpStatus.NOT_FOUND, null);
        verify(repo, never()).delete(any());
    }

    @Test
    void deletesOwnTemplate() {
        Template mine = stored(me.id(), "otp", Channel.SMS);
        when(repo.findById(mine.getId())).thenReturn(Optional.of(mine));

        service.delete(me, mine.getId());

        verify(repo).delete(mine);
    }

    @Test
    void listMarksSharedTemplatesReadOnly() {
        when(repo.findByClientIdOrClientIdIsNullOrderByNameAsc(me.id()))
                .thenReturn(List.of(stored(me.id(), "a-mine", Channel.SMS), stored(null, "b-shared", Channel.SMS)));

        List<TemplateView> views = service.list(me);

        assertThat(views).extracting(TemplateView::scope).containsExactly(TemplateScope.OWNED, TemplateScope.SHARED);
        assertThat(views).extracting(TemplateView::readOnly).containsExactly(false, true);
    }

    @Test
    void previewFillsKnownValuesAndReportsWhatIsMissing() {
        PreviewView p = service.preview(new PreviewRequest("Hi {{name}}", "Code {{code}} to {{recipient}}", Map.of("name", "Ann")));

        assertThat(p.subject()).isEqualTo("Hi Ann");
        assertThat(p.body()).isEqualTo("Code {{code}} to recipient@example.com");
        assertThat(p.requiredVariables()).containsExactly("name", "code");
        assertThat(p.missingVariables()).containsExactly("code");
    }
}
