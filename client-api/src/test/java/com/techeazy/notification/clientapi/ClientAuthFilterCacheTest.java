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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techeazy.notification.application.ApiKeys;
import com.techeazy.notification.application.RateLimitService;
import com.techeazy.notification.clientapi.RequestSignatureGate.Admission;
import com.techeazy.notification.domain.Client;
import com.techeazy.notification.domain.ClientStatus;
import com.techeazy.notification.persistence.ClientRepository;
import com.techeazy.notification.port.RateLimiter.Decision;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A disabled client or a rotated key stops working as soon as the change is announced, not when the cache expires. */
class ClientAuthFilterCacheTest {

    private static final String KEY = "ntf_cached-key";

    private final ClientRepository clients = mock(ClientRepository.class);
    private final RateLimitService rateLimits = mock(RateLimitService.class);
    private final RequestSignatureGate signatures = mock(RequestSignatureGate.class);
    private final ClientAuthFilter filter = new ClientAuthFilter(clients, rateLimits, new ObjectMapper(),
            new SimpleMeterRegistry(), Optional::empty, signatures);
    private final Client client = new Client();

    @BeforeEach
    void setUp() throws Exception {
        client.setId(UUID.randomUUID());
        client.setName("Acme");
        client.setStatus(ClientStatus.ACTIVE);
        client.setAllowedChannels("SMS");
        when(clients.findByApiKeyHash(ApiKeys.hash(KEY))).thenAnswer(call -> Optional.of(copyOf(client)));
        when(rateLimits.checkClientApi(any())).thenReturn(Decision.GRANTED);
        when(signatures.admit(any(), any(), any())).thenAnswer(call -> Admission.admit(call.getArgument(0)));
    }

    @Test
    void withoutAnAnnouncementTheCachedClientIsStillUsed() throws Exception {
        assertThat(status()).isEqualTo(200);
        client.setStatus(ClientStatus.DISABLED);

        assertThat(status()).isEqualTo(200);
    }

    @Test
    void anAnnouncedChangeTakesEffectOnTheNextRequest() throws Exception {
        assertThat(status()).isEqualTo(200);
        client.setStatus(ClientStatus.DISABLED);

        filter.forgetCachedClients();

        assertThat(status()).isEqualTo(401);
    }

    private int status() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/notifications");
        request.addHeader("X-API-Key", KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    private static Client copyOf(Client source) {
        Client copy = new Client();
        copy.setId(source.getId());
        copy.setName(source.getName());
        copy.setStatus(source.getStatus());
        copy.setAllowedChannels(source.getAllowedChannels());
        return copy;
    }
}
