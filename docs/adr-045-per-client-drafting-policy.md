# ADR-045: Per-client AI drafting policy: a switch and a budget of its own

- **Status:** Accepted
- **Date:** 2026-10-25
- **Amends:** ADR-041, ADR-044

## Context

ADR-041 gave every client the same monthly drafting budget, `client_monthly_token_budget`, and ADR-044 moved it to the
AI drafting page of the admin console. Setting it to 0 kept drafting for administrators only, so the console could
switch drafting on for every client or for none. ADR-041 left a per-client switch as a follow-up, because it needed a
schema change, an API and a page, and the shared budget limited the risk until then.

Operators now need finer control:

- Pause drafting for one client, for example one that sends personal data in its briefs or spends its budget on
  briefs that never become templates, without stopping it for everyone else.
- Give a client that drafts many templates, or one on a larger plan, more than the default, and give a trial client
  less.
- Try drafting with a few clients before offering it to all of them: keep the default at 0 and give those clients a
  budget of their own.
- See what each client has spent this month before changing its budget. Until now that meant querying
  `template_draft_usage` by hand.

## Decision

1. **One optional policy row per client**, in a new table `client_drafting_policy` (changeset
   `029-client-drafting-policy`). Columns:
   - `drafting_enabled`
   - `monthly_token_budget`: nullable. NULL keeps the default client budget, and a number replaces it.
   - `updated_at` and `updated_by`

   The table references `client` and its rows are deleted with the client. A client without a row has
   `ClientDraftingPolicy.DEFAULT`: drafting on, with the default budget. Existing clients therefore behave exactly as
   before, and no data is migrated. The table belongs to the `template-drafting` module, like the ledger and the
   settings. The `Client` entity and the dispatcher are not changed.
2. **The budget in effect for a client** is 0 when its policy is switched off. Otherwise it is the client's own budget
   if it has one, or else the default client budget. The rules of ADR-041 then apply unchanged:
   - A client whose budget in effect is 0 cannot draft. The status endpoint reports `enabled: false`, and a draft is
     refused with `DISABLED`.
   - Otherwise the client may spend the smaller of what is left of its own budget and what is left of the platform's.

   A budget of its own can be above the default, and it can be set while the default is 0. That is how drafting is
   offered to some clients only.
3. **Read on every call, not cached.** `TemplateDraftService` reads the client's policy by primary key on each status
   and draft request. A client that is switched off cannot start another draft, while the platform settings keep their
   ten-second cache (ADR-044). One indexed lookup per draft is small next to a call to the model.
4. **API.** Both endpoints are under the drafting settings, so they take that path's roles: any administrator may
   read, and only `ADMIN` may change. Every change goes to the audit log (ADR-019).
   - `GET /api/admin/settings/template-drafting/clients` lists every client by name with its policy, the budget in
     effect and the tokens it has spent this calendar month (UTC). The spend is one grouped query over the ledger.
   - `PUT /api/admin/settings/template-drafting/clients/{clientId}` saves `{draftingEnabled, monthlyTokenBudget?}`.
     Leaving out the budget returns the client to the default.
5. **Console.** The AI drafting page gets a **Clients** table that edits each client's policy in place:
   - an on/off switch;
   - the client's own budget, left empty for the default;
   - the budget in effect;
   - the tokens used this month, with the percentage of the budget.

   This also covers the follow-up in ADR-041 to show the drafting ledger per client.

## Options considered

- **Columns on `client`.** One table fewer, but every service loads the `Client` entity, the dispatcher included,
  and drafting is a concern of only two services. A separate table keeps the module's data together, like the
  ledger.
- **Cache the policy like the settings.** That saves a query per draft, but a client that is switched off could
  keep drafting until the cache expires. Changes are rare and the lookup is cheap.
- **A switch only, with no budget of its own.** Simpler, but it does not cover larger plans, trials or a gradual
  rollout. These are the reasons operators asked for it.
- **Let clients see their own policy.** The client status endpoint already shows how many tokens are left. The policy
  itself is an operator's decision, so it is not shown to the client.

## Consequences

Positive:
- Operators pause, limit or extend drafting for one client from the console, and the change applies to that
  client's next draft.
- Drafting can be rolled out to chosen clients first.
- Each client's spend this month is visible without a query.
- Existing clients and deployments are unchanged.

Negative / accepted:
- One more table, two endpoints and a console table to maintain.
- One extra indexed query for every status and draft request a client makes.
- A client's own budget does not change the platform budget. The platform budget still caps everyone together, so
  clients given large budgets of their own can exhaust it for the others.
- Policies are not shown to clients. A client that is switched off sees only that drafting is unavailable.
