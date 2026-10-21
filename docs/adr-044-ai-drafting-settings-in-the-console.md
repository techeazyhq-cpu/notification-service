# ADR-044: AI drafting settings in the admin console, drafting in the template editor

- **Status:** Accepted
- **Date:** 2026-10-21
- **Amends:** ADR-041

## Context

ADR-041 made AI drafting of templates optional and configured it only through the environment: `ANTHROPIC_API_KEY`
turned it on, and `TEMPLATE_DRAFTING_*` variables set the model, effort, token ceiling, budgets and rate limit. Turning
it on, rotating the key or raising a budget meant editing the deployment and restarting admin-api and client-api. An
operator who opened the console found no trace of the feature and no way to turn it on there.

The assistant was also a separate card above the template editor, with its own channel and category. Authors looked
for it in the create-template form, where they write the template, and had to pick the channel twice.

## Decision

1. **The settings live in the database and are edited in the admin console.** A new page, **AI drafting**, shows and
   changes: on or off, the Anthropic API key, the model, the effort, the token ceiling per reply, the platform's and
   each client's monthly token budget and the drafts per minute. `GET /api/admin/settings/template-drafting` reads them
   (any administrator); `PUT` changes them (role `ADMIN`, like other configuration). Every change is in the audit log
   (ADR-019), without its body.
2. **One row, `template_drafting_settings`** (changeset `026-template-drafting-settings`), with a check that only one
   row can exist. With no row, the environment defaults apply exactly as in ADR-041, so existing deployments are
   unchanged. Once saved, the row overrides them. The timeout, fallbacks, attempts and cache time stay in the
   environment: they are operational, not product settings.
3. **The key is write-only and encrypted.** It is encrypted with the secrets key under its own purpose
   (`template-drafting-api-key`, ADR-037) and re-encrypted after a key rotation like the other stored secrets. The API
   never returns it, only a hint of its last four characters and where it comes from (`STORED`, `ENVIRONMENT` or
   `NONE`). An empty key field keeps the key in effect. **Remove stored key** brings back `ANTHROPIC_API_KEY`, if it
   is set. The settings types mask the key in `toString`, so it cannot reach a log through them.
4. **Changes apply without a restart.** Admin-api and client-api read the settings for every draft through a cache of
   10 seconds (`template-drafting.settings-cache-ttl`), so a change reaches every instance within that time, and the
   instance that saved it at once. One Anthropic SDK client is kept per key and replaced when the key changes. The
   replaced client is not closed, because a draft may still be running on it.
5. **Drafting is part of the template editor.** **Draft with AI** sits inside the create and edit form of both UIs,
   between the channel and category and the subject and body. It uses the form's channel and category, and the draft
   fills the subject and body. While drafting is off, the admin editor says so and links to the settings page; the
   client editor shows nothing, since clients cannot turn it on.

## Options considered

- **Keep environment-only configuration.** Simplest, and settings stay in the deployment's version control. But the
  console is where operators run the platform, and a restart to rotate a key or raise a budget is out of step with
  providers and rate limits, which are already edited there.
- **Store the key in a secrets manager and only the rest in the database.** Stronger isolation of the key, but the
  platform has no secrets-manager integration yet. The key gets the same protection as provider credentials and
  client signing secrets, which are already in the database (ADR-036, ADR-037).
- **A generic key-value settings table.** Reusable for future settings, but loses typed columns and check
  constraints. One table per settings group keeps the schema self-describing. A second group can make it generic.
- **Push changes to instances with a Pulsar or Redis message.** Changes would apply at once everywhere, but that adds
  a messaging path for a rare change. A short cache is enough, as for rate-limit policies.
- **Validate the key with a test call when it is saved.** Catches a mistyped key early, but every save would spend
  tokens and fail when the API is briefly unreachable. A rejected key shows up as `DRAFTING_MODEL_UNAVAILABLE` on the
  first draft and in the admin API's log.

## Consequences

Positive: operators turn drafting on, rotate the key and change budgets from the console without a restart; the key
is encrypted at rest and never shown; deployments that configure drafting through the environment keep working
unchanged; authors find drafting in the template editor and choose the channel and category once.

Negative / accepted:

- The key is now in the database too. A copy of the database alone does not reveal it, but someone with the database
  and the secrets key does, as for provider credentials.
- A change takes up to 10 seconds to reach the other instances, so a draft may still use the previous settings during
  that time.
- Settings in the database are not in the deployment's version control. The audit log records who changed them and
  when.
