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

import com.techeazy.notification.domain.Client;
import com.techeazy.notification.domain.ClientStatus;
import com.techeazy.notification.drafting.ClientDraftingAllowance;
import com.techeazy.notification.drafting.ClientDraftingPolicies;
import com.techeazy.notification.drafting.ClientDraftingPolicy;
import com.techeazy.notification.drafting.TemplateDraftService;
import com.techeazy.notification.persistence.ClientRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Each client's AI drafting policy (ADR-045): whether the client may draft, and its own monthly token budget in place
 * of the default client budget on the drafting settings, shown with what it has spent this month. Reading is for any
 * administrator, changing for {@code ADMIN}, and every change is in the audit log (ADR-019). A change applies to the
 * client's next draft.
 */
@RestController
@RequestMapping("/api/admin/settings/template-drafting/clients")
class ClientDraftingPoliciesController {

    /**
     * @param monthlyTokenBudget          the client's own budget; null when it has the default client budget
     * @param effectiveMonthlyTokenBudget the budget in effect this month; 0 when the client cannot draft
     */
    record ClientDraftingView(UUID clientId, String clientName, ClientStatus clientStatus, boolean draftingEnabled,
                              Long monthlyTokenBudget, long effectiveMonthlyTokenBudget, long tokensUsedThisMonth) {}

    /** @param monthlyTokenBudget the client's own tokens per month; absent for the default client budget */
    record PolicyInput(boolean draftingEnabled, @Min(0) @Max(10_000_000_000L) Long monthlyTokenBudget) {}

    private final ClientRepository clients;
    private final ClientDraftingPolicies policies;
    private final TemplateDraftService drafting;

    ClientDraftingPoliciesController(ClientRepository clients, ClientDraftingPolicies policies,
                                     TemplateDraftService drafting) {
        this.clients = clients;
        this.policies = policies;
        this.drafting = drafting;
    }

    @GetMapping
    List<ClientDraftingView> list() {
        List<Client> all = clients.findAll().stream().sorted(Comparator.comparing(Client::getName)).toList();
        Map<UUID, ClientDraftingAllowance> allowances = drafting.allowances(all.stream().map(Client::getId).toList());
        return all.stream().map(client -> view(client, allowances.get(client.getId()))).toList();
    }

    @PutMapping("/{clientId}")
    ClientDraftingView save(Authentication authentication, @PathVariable UUID clientId,
                            @Valid @RequestBody PolicyInput in) {
        Client client = clients.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Client not found"));
        policies.save(clientId, new ClientDraftingPolicy(in.draftingEnabled(), in.monthlyTokenBudget()),
                authentication.getName());
        return view(client, drafting.allowances(List.of(clientId)).get(clientId));
    }

    private static ClientDraftingView view(Client client, ClientDraftingAllowance allowance) {
        return new ClientDraftingView(client.getId(), client.getName(), client.getStatus(),
                allowance.policy().enabled(), allowance.policy().monthlyTokenBudget(), allowance.monthlyTokenBudget(),
                allowance.tokensUsedThisMonth());
    }
}
