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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.Client;
import com.techeazy.notification.domain.ClientStatus;
import com.techeazy.notification.infra.AesGcmCipher;
import com.techeazy.notification.infra.ClientChangeBroadcast;
import com.techeazy.notification.infra.ClientSigningSecrets;
import com.techeazy.notification.persistence.ClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Administrators issue, require and remove a client's request-signing secret (ADR-036). */
class ClientsControllerTest {

    private final ClientRepository repository = mock(ClientRepository.class);
    private final ClientSigningSecrets secrets = new ClientSigningSecrets(new AesGcmCipher("credentials-key"));
    private final ClientChangeBroadcast changes = mock(ClientChangeBroadcast.class);
    private final ObjectMapper json = new ObjectMapper();
    private final Client client = new Client();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        client.setId(UUID.randomUUID());
        client.setName("Acme");
        client.setStatus(ClientStatus.ACTIVE);
        client.setAllowedChannelSet(Set.of(Channel.SMS));
        client.setApiKeyPrefix("ntf_abcdef");
        client.setCreatedAt(Instant.parse("2026-10-01T00:00:00Z"));
        when(repository.findById(client.getId())).thenReturn(Optional.of(client));
        mvc = MockMvcBuilders.standaloneSetup(new ClientsController(repository, secrets, changes))
                .setControllerAdvice(new AdminErrorHandler(Optional::empty)).build();
    }

    @Test
    void everyChangeToAClientIsAnnouncedSoClientApiInstancesDropTheirCachedCopy() throws Exception {
        perform(post(path("/signing-secret")));
        perform(put(path("/signing-required")).contentType(MediaType.APPLICATION_JSON).content("{\"required\":true}"));
        perform(post(path("/rotate-key")));
        perform(delete(path("/signing-secret")));
        perform(put(path("")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme\",\"status\":\"DISABLED\",\"allowedChannels\":[\"SMS\"]}"));

        verify(changes, times(5)).clientChanged(client.getId());
    }

    @Test
    void anIssuedSecretIsShownOnceAndStoredOnlyEncrypted() throws Exception {
        JsonNode issued = json.readTree(perform(post(path("/signing-secret"))).getContentAsString());

        String secret = issued.get("signingSecret").asText();
        assertThat(secret).matches("nss_[0-9a-f]{64}");
        assertThat(issued.at("/client/signingEnabled").asBoolean()).isTrue();
        assertThat(issued.at("/client/signingRequired").asBoolean()).isFalse();
        assertThat(client.getSigningSecret()).isNotEqualTo(secret).doesNotContain(secret.substring(4));
        assertThat(secrets.decryptForUse(client.getSigningSecret())).isEqualTo(secret);
    }

    @Test
    void issuingAgainReplacesThePreviousSecret() throws Exception {
        String first = json.readTree(perform(post(path("/signing-secret"))).getContentAsString()).get("signingSecret").asText();
        String second = json.readTree(perform(post(path("/signing-secret"))).getContentAsString()).get("signingSecret").asText();

        assertThat(second).isNotEqualTo(first);
        assertThat(secrets.decryptForUse(client.getSigningSecret())).isEqualTo(second);
    }

    @Test
    void signaturesCanOnlyBeRequiredOnceTheClientHasASecret() throws Exception {
        MockHttpServletResponse withoutSecret = perform(requireSignatures(true));

        assertThat(withoutSecret.getStatus()).isEqualTo(409);
        assertThat(client.isSigningRequired()).isFalse();

        perform(post(path("/signing-secret")));
        MockHttpServletResponse withSecret = perform(requireSignatures(true));

        assertThat(withSecret.getStatus()).isEqualTo(200);
        assertThat(json.readTree(withSecret.getContentAsString()).get("signingRequired").asBoolean()).isTrue();
        assertThat(client.isSigningRequired()).isTrue();
    }

    @Test
    void removingTheSecretAlsoStopsRequiringSignatures() throws Exception {
        perform(post(path("/signing-secret")));
        perform(requireSignatures(true));

        JsonNode view = json.readTree(perform(delete(path("/signing-secret"))).getContentAsString());

        assertThat(client.getSigningSecret()).isNull();
        assertThat(client.isSigningRequired()).isFalse();
        assertThat(view.get("signingEnabled").asBoolean()).isFalse();
        assertThat(view.get("signingRequired").asBoolean()).isFalse();
    }

    private RequestBuilder requireSignatures(boolean required) {
        return put(path("/signing-required")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"required\":" + required + "}");
    }

    private String path(String suffix) {
        return "/api/admin/clients/" + client.getId() + suffix;
    }

    private MockHttpServletResponse perform(RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }
}
