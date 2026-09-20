package com.techeazy.notification.billing.application;

import com.techeazy.notification.billing.application.port.AccountRepository;
import com.techeazy.notification.billing.application.port.PlanRepository;
import com.techeazy.notification.billing.domain.BillingNotFoundException;
import com.techeazy.notification.billing.domain.InvalidBillingStateException;
import com.techeazy.notification.billing.domain.Plan;

import java.util.List;
import java.util.UUID;

/** Creates and maintains price lists. A plan in use keeps its currency, so balances and invoices stay consistent. */
public class PlanCatalog {

    private final PlanRepository plans;
    private final AccountRepository accounts;

    public PlanCatalog(PlanRepository plans, AccountRepository accounts) {
        this.plans = plans;
        this.accounts = accounts;
    }

    public Plan create(PlanDraft draft) {
        if (plans.findByName(draft.name()).isPresent()) {
            throw new InvalidBillingStateException("A plan named '" + draft.name() + "' already exists");
        }
        return plans.save(toPlan(UUID.randomUUID(), draft));
    }

    public Plan update(UUID id, PlanDraft draft) {
        Plan existing = get(id);
        plans.findByName(draft.name()).filter(other -> !other.id().equals(id)).ifPresent(other -> {
            throw new InvalidBillingStateException("A plan named '" + draft.name() + "' already exists");
        });
        Plan updated = toPlan(id, draft);
        if (!updated.currency().equals(existing.currency()) && accounts.existsWithPlan(id)) {
            throw new InvalidBillingStateException("The currency of a plan that accounts use cannot be changed");
        }
        return plans.save(updated);
    }

    public Plan get(UUID id) {
        return plans.findById(id).orElseThrow(() -> new BillingNotFoundException("Plan"));
    }

    public List<Plan> list() {
        return plans.findAll();
    }

    private static Plan toPlan(UUID id, PlanDraft draft) {
        return new Plan(id, draft.name(), draft.currency(), draft.platformFee(), draft.taxRate(), draft.rates(), draft.active());
    }
}
