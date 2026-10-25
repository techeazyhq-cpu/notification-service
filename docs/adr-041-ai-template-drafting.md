# ADR-041: Optional AI drafting of message templates

- **Status:** Accepted, amended by ADR-044 (any LLM provider, settings edited in the admin console, drafting in the
  template editor), ADR-045 (per-client switch and budget), ADR-046 (example templates) and ADR-047 (prompt evaluation)
- **Date:** 2026-10-21

## Context

Writing a good template takes more care than it looks. An SMS that grows one character past 160 is billed as two, and
one emoji cuts a segment to 70 characters. An e-mail needs a subject and HTML that mail clients render. A WhatsApp
template has a 1024-character body, and Meta refuses one that starts or ends with a placeholder. Every template must use
the `{{name}}` syntax exactly, or sends fail with `TEMPLATE_VARIABLE_MISSING` (ADR-002). Administrators write the shared
templates; since ADR-002, clients write their own in the client console or through `/v1/templates`.

A language model can write a first draft from a sentence like "remind the customer of tomorrow's appointment, friendly,
with their first name and the time". It also brings costs and risks: model calls cost money, the brief could carry
personal data to a third party, a model can produce text that breaks the channel's rules or invents facts, and the
project should not come to depend on AI. The project's dependencies must also stay Apache-2.0-compatible.

## Decision

Add **optional** AI drafting of SMS, e-mail and WhatsApp templates, for administrators (shared templates) and for
clients (their own). The model drafts; a person reviews, edits and saves. It is off unless an API key is configured.

1. **Shape: one call, one draft, nothing saved.** The author describes the message: channel, category, tone (one of
   five), purpose (10 to 1000 characters) and the names of up to 15 variables. The service returns a draft with a
   subject (e-mail only), a body, the variables it uses, warnings and, for SMS, the estimated length. The UI puts the
   draft in the template editor. Saving still goes through `POST /api/admin/templates` or `POST /v1/templates` with
   their own validation. The feature never saves, sends or edits a template.
2. **Endpoints.** `GET /api/admin/templates/drafting` and `GET /v1/templates/drafting` say whether drafting is on for
   the caller, which model it uses, how many tokens the caller has left this month and the per-minute limit; the UIs
   show the assistant only when it is on. `POST /api/admin/templates/drafts` (role `ADMIN`, like changing a template)
   and `POST /v1/templates/drafts` (the client's API key; refused with `CHANNEL_NOT_ALLOWED` for a channel the client
   may not use) return the draft.
3. **A module of its own.** `template-drafting` holds the service, the channel rules, the prompts, the usage ledger and
   the only class that knows the Anthropic SDK (`ClaudeDraftModel`, behind the `DraftModel` port). Only admin-api and
   client-api depend on it, so the dispatcher never carries the SDK.
4. **Claude through the official Java SDK.** `com.anthropic:anthropic-java` (MIT; its OkHttp, Jackson, Kotlin standard
   library and victools dependencies are Apache-2.0). Model `claude-opus-5-5` by default (`TEMPLATE_DRAFTING_MODEL`),
   effort `low` (a short template needs little thinking, and thinking tokens are billed), `max_tokens` 8000.
   Structured outputs constrain the reply to a JSON object `{subject, body}`, so it is parsed, not scraped from prose.
   Server-side fallbacks (`fallbacks: "default"`) let the API retry a declined brief on its recommended fallback model.
   A reply stopped by `refusal` becomes `DRAFT_DECLINED`; one cut off at `max_tokens`, or that is not the JSON asked
   for, counts as breaking the rules.
5. **Only the brief leaves the platform.** The model gets a fixed system prompt and the brief: channel, category, tone,
   purpose and variable names. Never recipients, variable values, existing templates or messages. The UI tells authors
   not to put personal data in the description. The system prompt tells the model to treat the brief as a description,
   not as instructions, and never to invent amounts, dates, codes, names or links that a placeholder should supply.
6. **The rules are checked here, not trusted to the model.** `ChannelRules` checks every reply. Violations: SMS longer
   than 3 segments (GSM-7: 160 characters, 153 per part; UCS-2: 70 and 67; each placeholder counted as 12 characters)
   or with a subject or HTML; e-mail without a subject, with a subject over 150 characters, on several lines or with
   markup, a body over 10000 characters, or scripts, styles, forms, embedded content, event handlers or `javascript:`
   links; WhatsApp with a subject or HTML, or over 1024 characters; on every channel, an empty body, a malformed
   placeholder, or a placeholder that was not asked for (`{{recipient}}` is always allowed). Warnings: an SMS of 2 or 3
   segments, UCS-2 encoding, an unused variable, a WhatsApp text that starts or ends with a placeholder. A reply with
   violations is sent back once with the broken rules listed (`template-drafting.max-attempts`, default 2). If the
   correction breaks them too, no draft is returned: `DRAFT_RULES_NOT_MET` (502) lists what was wrong.
7. **Cost controls.** In order, before the model is called:
   - a per-caller rate limit of 5 drafts a minute (`TEMPLATE_DRAFTING_REQUESTS_PER_MINUTE`), one bucket per
     administrator and one per client, on the existing Redis token bucket. Refusals are `RATE_LIMITED` with
     `Retry-After`;
   - a platform budget of 2,000,000 tokens per calendar month in UTC (`TEMPLATE_DRAFTING_MONTHLY_TOKENS`), and for
     clients a budget of 200,000 per client per month (`TEMPLATE_DRAFTING_CLIENT_MONTHLY_TOKENS`). A client sees and
     spends the smaller of the two remainders. A client budget of 0 keeps drafting for administrators only. Refusals
     are `DRAFT_BUDGET_EXHAUSTED` (429).
   The budget counts input and output tokens, thinking included, from each reply's `usage`. It is kept in the
   append-only table `template_draft_usage` (changeset `025-template-draft-usage`): one row per draft that reached the
   model, with the actor, client, channel, model, tokens and outcome, never the brief or the draft. A table rather than
   Redis counters, because a budget must survive a Redis flush and be shared by every instance; the sum is over an
   indexed month of rows, which stays small. At current prices a draft costs a few US cents, so the default platform
   budget is in the order of tens of dollars a month.
8. **Every use is audited (ADR-019).** The administrator's `POST` is recorded by the existing audit filter. A client's
   request is written to the same `admin_audit_event` table by `DraftAuditTrail` in client-api, with the actor
   `client:<name>`, no admin role, the route, the status and outcome, the source address and the user agent; the body
   is not stored. Every outcome is recorded: drafted, refused, rejected or failed. Administrators see tenants' use next
   to their own on the **Audit log** page.
9. **Observability.** `notification.drafting.drafts{outcome}` counts drafts by how they ended,
   `notification.drafting.tokens` counts tokens spent.
10. **Configuration.** `ANTHROPIC_API_KEY` on admin-api and client-api turns drafting on; Docker Compose passes it to
   those two services only, never to the dispatcher. The other settings are under `template-drafting.*` with the
   defaults above. New error codes: `DRAFT_DECLINED` (NS-1006), `DRAFTING_NOT_AVAILABLE` (NS-2015),
   `DRAFT_BUDGET_EXHAUSTED` (NS-5002), `DRAFTING_MODEL_UNAVAILABLE` (NS-8002), `DRAFT_RULES_NOT_MET` (NS-8003).

## Options considered

- **No AI: a library of example templates per channel and category.** No cost, no third party, and worth having anyway,
  but it does not adapt to a business's own purpose and variables. Drafting stays optional, so this is still possible
  later.
- **A local open-weights model.** No data leaves the platform and there is no per-call price, but a model good enough
  to write well needs gigabytes of memory and a GPU, far beyond the laptop limits of the Compose stack, and many model
  licences are not Apache-2.0-compatible.
- **Admin-only.** Smaller exposure of the operator's API credit and no client-facing abuse surface. Rejected by the
  product decision to offer it to clients too; the per-client budget, rate limit and audit trail are what make that
  safe, and a client budget of 0 restores admin-only.
- **Tool use instead of structured outputs.** Also yields a typed reply, but adds a tool loop for what is one request
  and one answer. Structured outputs give the JSON directly.
- **Let the model validate its own output.** Cheaper by a call, but the model counts characters and segments badly,
  and the rules decide billing and delivery. They are checked deterministically, and the model only corrects.
- **Return a draft that breaks the rules, marked invalid.** Sometimes useful as a starting point, but the author could
  save a template that fails or costs more than expected. One automatic correction recovers most cases.
- **Budget counters in Redis.** Simpler and faster, but lost on a flush or failover and not durable enough for money.
- **Store the brief and the draft** for quality review. Useful for tuning the prompt, but the brief may hold personal
  data despite the warning, and storing it would make the platform hold more than it needs. Not stored.
- **Per-client on/off switch in the database.** More control than one client budget for everyone, but needs a schema
  change, an admin UI and an API. Left as a follow-up; the per-client budget bounds the exposure meanwhile.

## Consequences

Positive: authors get a first draft that already meets the channel's rules and uses the right placeholders; drafts
are reviewed before anything is saved; no recipient data or variable values reach the model; cost is bounded per
caller, per client and per platform, and is visible in the ledger, the metrics and the UI; every use is audited; with
no key the feature does not exist for users, and the dispatcher and the send path are unchanged.

Negative / accepted:

- The brief goes to Anthropic, a third party. An author can still type personal data into it; the UI warns against it
  and nothing filters it. Operators who cannot accept this leave the key unset.
- The budget is checked before a call and charged after it, so concurrent drafts can overshoot it by at most
  `max_tokens` per draft in flight. With fallbacks, top-level `usage` covers the attempt that produced the reply; a
  refused first attempt is not added. Both are small next to the budget.
- The rate limiter fails open when Redis is down (ADR-024's alert covers it); the monthly budgets still apply.
- SMS length is an estimate: the real values are only known at send time.
- A model can still write text that is valid but wrong or off-tone. Review by a person is the control, not the rules.
- The admin audit table now also holds client actors, marked by the `client:` prefix and no role.

## Follow-ups

- Helm: pass the key from a Secret to admin-api and client-api only. Until then `config.extraEnv` can set it, but it
  then reaches the dispatcher too.
- A per-client switch and per-client budget overrides in the admin console. Done in ADR-045.
- A library of example templates that needs no model. Done in ADR-046.
- Show the drafting ledger (tokens per client per month) in the admin console. Done in ADR-045, as each client's
  spend this month.
- A prompt evaluation set, so a model or prompt change can be measured before it ships. Done in ADR-047.
