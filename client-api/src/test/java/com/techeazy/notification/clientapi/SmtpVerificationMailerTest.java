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

import com.techeazy.notification.application.ProviderDestinationPolicy;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.ProviderConfig;
import com.techeazy.notification.domain.ProviderType;
import com.techeazy.notification.infra.AesGcmCipher;
import com.techeazy.notification.infra.ProviderSecrets;
import com.techeazy.notification.persistence.ProviderConfigRepository;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Sender confirmation e-mails go through the configured SMTP provider, so its host is subject to the same policy. */
class SmtpVerificationMailerTest {

    @Test
    void anSmtpProviderOnAnInternalHostIsNotContacted() {
        List<String> lookedUp = new CopyOnWriteArrayList<>();
        ProviderDestinationPolicy destinations = new ProviderDestinationPolicy(Set.of(), true, host -> {
            lookedUp.add(host);
            return List.of(InetAddress.getByName("10.0.0.5"));
        });
        ProviderConfig internalRelay = new ProviderConfig();
        internalRelay.setType(ProviderType.SMTP);
        internalRelay.setChannel(Channel.EMAIL);
        internalRelay.getSettings().put("host", "relay.internal.test");
        internalRelay.getSettings().put("from", "no-reply@example.com");
        ProviderConfigRepository providers = mock(ProviderConfigRepository.class);
        when(providers.findByChannelAndEnabledTrueOrderByPriorityAsc(Channel.EMAIL)).thenReturn(List.of(internalRelay));
        SmtpVerificationMailer mailer = new SmtpVerificationMailer(providers,
                new ProviderSecrets(new AesGcmCipher("test-key")), destinations);

        assertThatThrownBy(() -> mailer.send("owner@example.com", "Acme", "https://example.com/verify"))
                .isInstanceOf(ApiException.class).hasMessageContaining("could not be sent");
        assertThat(lookedUp).containsExactly("relay.internal.test");
    }
}
