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
import com.techeazy.notification.error.ErrorCode;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClientAuthFilterTest {

    private static final String KEY = "ntf_test-key";

    private final ClientRepository clients = mock(ClientRepository.class);
    private final RateLimitService rateLimits = mock(RateLimitService.class);
    private final RequestSignatureGate signatures = mock(RequestSignatureGate.class);
    private final ClientAuthFilter filter = new ClientAuthFilter(clients, rateLimits, new ObjectMapper(),
            new SimpleMeterRegistry(), Optional::empty, signatures);
    private final Client client = new Client();

    @BeforeEach
    void setUp() {
        client.setId(UUID.randomUUID());
        client.setName("Acme");
        client.setStatus(ClientStatus.ACTIVE);
        client.setAllowedChannels("SMS");
        client.setSigningSecret("encrypted");
        client.setSigningRequired(true);
        when(clients.findByApiKeyHash(ApiKeys.hash(KEY))).thenReturn(Optional.of(client));
        when(rateLimits.checkClientApi(client.getId())).thenReturn(Decision.GRANTED);
    }

    @Test
    void aRequestWithABadSignatureIsRefusedWithoutSpendingTheClientsQuota() throws Exception {
        when(signatures.admit(any(), eq(client.getId()), any()))
                .thenReturn(Admission.refuse(ErrorCode.SIGNATURE_INVALID, "bad signature"));

        MockHttpServletResponse response = send();

        assertThat(response.getStatus()).isEqualTo(ErrorCode.SIGNATURE_INVALID.httpStatus().orElseThrow());
        verify(rateLimits, never()).checkClientApi(any());
    }

    @Test
    void aCorrectlySignedRequestOverTheQuotaIsRateLimited() throws Exception {
        when(signatures.admit(any(), eq(client.getId()), any())).thenAnswer(call -> Admission.admit(call.getArgument(0)));
        when(rateLimits.checkClientApi(client.getId())).thenReturn(new Decision(false, 1_500));

        MockHttpServletResponse response = send();

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("2");
    }

    @Test
    void aCorrectlySignedRequestWithinTheQuotaReachesTheController() throws Exception {
        when(signatures.admit(any(), eq(client.getId()), any())).thenAnswer(call -> Admission.admit(call.getArgument(0)));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request(), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(chain.getRequest().getAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE)).isNotNull();
    }

    private MockHttpServletResponse send() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(), response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/notifications");
        request.addHeader("X-API-Key", KEY);
        return request;
    }
}
