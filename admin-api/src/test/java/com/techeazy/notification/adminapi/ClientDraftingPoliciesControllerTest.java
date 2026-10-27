/*
 * Copyright 2026 Vasantha Kumar Kesavan
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
 * @author Vasantha Kumar Kesavan <vasantha.kumar@hotmail.com>
 */
package com.techeazy.notification.adminapi;

import com.techeazy.notification.domain.Client;
import com.techeazy.notification.domain.ClientStatus;
import com.techeazy.notification.drafting.ClientDraftingAllowance;
import com.techeazy.notification.drafting.ClientDraftingPolicies;
import com.techeazy.notification.drafting.ClientDraftingPolicy;
import com.techeazy.notification.drafting.TemplateDraftService;
import com.techeazy.notification.persistence.ClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Administrators switch AI drafting on or off per client and give a client its own monthly budget (ADR-045). */
class ClientDraftingPoliciesControllerTest {

    private static final String PATH = "/api/admin/settings/template-drafting/clients";

    private final ClientRepository clients = mock(ClientRepository.class);
    private final ClientDraftingPolicies policies = mock(ClientDraftingPolicies.class);
    private final TemplateDraftService drafting = mock(TemplateDraftService.class);
    private final UsernamePasswordAuthenticationToken alice =
            new UsernamePasswordAuthenticationToken("alice", null, List.of());
    private final Client acme = client("acme");
    private final Client zeta = client("zeta");
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ClientDraftingPoliciesController(clients, policies, drafting))
                .setControllerAdvice(new AdminErrorHandler(Optional::empty)).build();
        when(clients.findAll()).thenReturn(List.of(zeta, acme));
        when(clients.findById(acme.getId())).thenReturn(Optional.of(acme));
        when(drafting.allowances(anyCollection())).thenReturn(Map.of(
                acme.getId(), new ClientDraftingAllowance(new ClientDraftingPolicy(false, 5000L), 0, 1200),
                zeta.getId(), new ClientDraftingAllowance(ClientDraftingPolicy.DEFAULT, 200_000, 0)));
    }

    private static Client client(String name) {
        Client client = new Client();
        client.setId(UUID.randomUUID());
        client.setName(name);
        client.setStatus(ClientStatus.ACTIVE);
        return client;
    }

    private MockHttpServletResponse save(UUID clientId, String body) throws Exception {
        return mvc.perform(put(PATH + "/" + clientId).principal(alice).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn().getResponse();
    }

    @Test
    void everyClientIsListedByNameWithItsPolicyBudgetAndSpendThisMonth() throws Exception {
        MockHttpServletResponse response = mvc.perform(get(PATH).principal(alice)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualToIgnoringWhitespace("""
                [{"clientId":"%s","clientName":"acme","clientStatus":"ACTIVE","draftingEnabled":false,
                  "monthlyTokenBudget":5000,"effectiveMonthlyTokenBudget":0,"tokensUsedThisMonth":1200},
                 {"clientId":"%s","clientName":"zeta","clientStatus":"ACTIVE","draftingEnabled":true,
                  "monthlyTokenBudget":null,"effectiveMonthlyTokenBudget":200000,"tokensUsedThisMonth":0}]
                """.formatted(acme.getId(), zeta.getId()));
    }

    @Test
    void aPolicyIsSavedAsTheSignedInAdministratorAndTheClientShownAsItNowStands() throws Exception {
        MockHttpServletResponse response = save(acme.getId(),
                "{\"draftingEnabled\":false,\"monthlyTokenBudget\":5000}");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"clientName\":\"acme\"")
                .contains("\"tokensUsedThisMonth\":1200");
        verify(policies).save(acme.getId(), new ClientDraftingPolicy(false, 5000L), "alice");
    }

    @Test
    void withoutABudgetTheClientGoesBackToTheDefault() throws Exception {
        save(acme.getId(), "{\"draftingEnabled\":true}");

        verify(policies).save(acme.getId(), ClientDraftingPolicy.DEFAULT, "alice");
    }

    @Test
    void anUnknownClientIsNotFound() throws Exception {
        assertThat(save(UUID.randomUUID(), "{\"draftingEnabled\":true}").getStatus()).isEqualTo(404);
        verify(policies, never()).save(any(), any(), anyString());
    }

    @Test
    void aNegativeBudgetIsRefused() throws Exception {
        assertThat(save(acme.getId(), "{\"draftingEnabled\":true,\"monthlyTokenBudget\":-1}").getStatus())
                .isEqualTo(400);
        verify(policies, never()).save(any(), any(), anyString());
    }
}
