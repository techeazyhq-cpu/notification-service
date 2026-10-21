# ADR-044: Provider-neutral AI drafting, configured in the admin console and used in the template editor

- **Status:** Accepted
- **Date:** 2026-10-21
- **Amends:** ADR-041

## Context

ADR-041 made AI drafting of templates optional, built it on Anthropic's Claude only, and configured it only through
the environment: `ANTHROPIC_API_KEY` turned it on, and `TEMPLATE_DRAFTING_*` variables set the model, effort, token
ceiling, budgets and rate limit. Three things did not fit how the platform is run:

- Operators want to choose the LLM per environment: a local Ollama while developing and testing, and Anthropic,
  OpenAI, Google Gemini or DeepSeek in production, depending on cost, data residency and contracts.
- Turning drafting on, rotating a key or raising a budget meant editing the deployment and restarting admin-api and
  client-api. An operator who opened the console found no trace of the feature.
- The assistant was a separate card above the template editor, with its own channel and category. Authors looked for
  it in the create-template form and had to pick the channel twice.

## Decision

1. **Any LLM provider, through two protocols.** `LlmProvider` lists `ANTHROPIC`, `OPENAI`, `GEMINI`, `DEEPSEEK`,
   `OLLAMA` and `OPENAI_COMPATIBLE`. Anthropic is called through its own SDK, as in ADR-041. Every other provider is
   called through OpenAI's Chat Completions API (`POST <base URL>/chat/completions`), which OpenAI, Gemini (its
   OpenAI-compatible endpoint), DeepSeek and Ollama all offer. `OPENAI_COMPATIBLE` covers any other server that
   speaks it: vLLM, LM Studio, an LLM gateway. The `OpenAiCompatibleDraftModel` adapter uses the JDK HTTP client and
   Jackson, so no SDK is added per provider. What differs between providers is data on the enum, not branches in
   code: the default endpoint, whether a key is needed, whether the reply can be held to a strict JSON schema or only
   to "a JSON object", and the name of the token-ceiling field (`max_completion_tokens` for OpenAI, `max_tokens`
   elsewhere). The adapter removes `<think>` reasoning and Markdown fences that local models put around the JSON. The
   channel rules (ADR-041 §6) check every reply whatever the provider, so a weaker model costs a correction, not a
   broken template.
2. **The settings live in the database and are edited in the admin console.** A new page, **AI drafting**, shows and
   changes: on or off, the provider, its base URL (optional, except for `OPENAI_COMPATIBLE`), the API key (optional
   for Ollama and `OPENAI_COMPATIBLE`), the model, the effort (Anthropic only), the token ceiling per reply, the
   platform's and each client's monthly token budget and the drafts per minute.
   `GET /api/admin/settings/template-drafting` reads them (any administrator); `PUT` changes them (role `ADMIN`, like
   other configuration). Every change is in the audit log (ADR-019), without its body.
3. **One row, `template_drafting_settings`** (changesets `026-template-drafting-settings` and, for the provider and
   base URL, `027-template-drafting-provider`; a row saved before 027 stays on Anthropic), with checks that only one
   row exists, the provider is known and `OPENAI_COMPATIBLE` has a base URL. With no row, the environment applies:
   `TEMPLATE_DRAFTING_PROVIDER` (default `ANTHROPIC`), `TEMPLATE_DRAFTING_BASE_URL`, `TEMPLATE_DRAFTING_API_KEY`
   (falling back to `ANTHROPIC_API_KEY`, so ADR-041 deployments keep working unchanged) and the other
   `TEMPLATE_DRAFTING_*` variables. Once saved, the row overrides them. The timeout, fallbacks, attempts and cache
   time stay in the environment: they are operational, not product settings.
4. **The endpoint passes the provider destination policy (ADR-022),** like any provider's: a public HTTPS host, or a
   host the operator lists in `PROVIDER_TRUSTED_HOSTS`. It is checked when the settings are saved (refused with
   `PROVIDER_DESTINATION_REFUSED` and the reason) and again before every draft, so an endpoint set only in the
   environment, or a host that later resolves elsewhere, is caught too. Docker Compose trusts and maps
   `host.docker.internal`, the developer's machine, so a local Ollama works at
   `http://host.docker.internal:11434/v1`.
5. **The key is write-only and encrypted.** It is encrypted with the secrets key under its own purpose
   (`template-drafting-api-key`, ADR-037) and re-encrypted after a key rotation like the other stored secrets. The API
   never returns it, only a hint of its last four characters and where it comes from (`STORED`, `ENVIRONMENT` or
   `NONE`). An empty key field keeps the key in effect. **Remove stored key** brings back the environment's key, if
   any. The settings types mask the key in `toString`, so it cannot reach a log through them.
6. **Changes apply without a restart.** Admin-api and client-api read the settings for every draft through a cache of
   10 seconds (`template-drafting.settings-cache-ttl`), so a change reaches every instance within that time, and the
   instance that saved it at once. One Anthropic SDK client is kept per key and endpoint and replaced when they
   change. The replaced client is not closed, because a draft may still be running on it.
7. **Drafting is part of the template editor.** **Draft with AI** sits inside the create and edit form of both UIs,
   between the channel and category and the subject and body. It uses the form's channel and category, and the draft
   fills the subject and body. While drafting is off, the admin editor says so and links to the settings page; the
   client editor shows nothing, since clients cannot turn it on.

## Options considered

- **One SDK per provider** (Anthropic, OpenAI, Google, DeepSeek). Each provider's newest features, but a dependency,
  a licence review and an adapter for each, and still nothing for Ollama or a gateway. The Chat Completions API is
  the common ground they share.
- **Only the OpenAI-compatible API, Anthropic included.** Anthropic offers an OpenAI-compatible endpoint too, but
  ADR-041's structured outputs, effort and server-side fallbacks work only through its own API, and that adapter is
  already built and tested.
- **A framework such as Spring AI or LangChain4j.** Many providers behind one interface, but a large dependency
  surface and its own release cadence for one request and one reply. Two small adapters are enough, and a
  framework can replace them if drafting grows into conversations or tools.
- **Keep environment-only configuration.** Simplest, and settings stay in the deployment's version control. But the
  console is where operators run the platform, and providers and rate limits are already edited there.
- **Store the key in a secrets manager.** Stronger isolation, but the platform has no secrets-manager integration yet.
  The key gets the same protection as provider credentials and client signing secrets (ADR-036, ADR-037).
- **Push changes to instances with a message.** Changes would apply at once everywhere, but that adds a messaging
  path for a rare change. A short cache is enough, as for rate-limit policies.
- **Test the key and model when they are saved.** Catches a mistyped key early, but every save would spend tokens and
  fail while the provider is briefly unreachable. A rejected key shows up as `DRAFTING_MODEL_UNAVAILABLE` on the first
  draft and in the service's log.

## Consequences

Positive: operators pick the LLM per environment, a local Ollama included, and change it, its key and its budgets from
the console without a restart; the key is encrypted at rest and never shown; the endpoint cannot be pointed at the
platform's own network; deployments configured through the environment for ADR-041 keep working unchanged; authors
find drafting in the template editor and choose the channel and category once.

Negative / accepted:

- Draft quality depends on the model chosen. Small local models break the channel rules more often; the rules and
  the one correction still keep a bad draft out of the editor, at the cost of a second call.
- Providers differ at the edges of the shared API: some ignore the JSON schema, some report no token usage (counted
  as 0, so a provider that hides usage is not held to the budgets), and some reasoning models need settings
  this page does not offer. `OPENAI_COMPATIBLE` asks only for a JSON object, the least that every server supports.
- The brief now goes to whichever provider is configured. Operators choose one whose data terms they accept, or a
  local Ollama, where nothing leaves their network.
- The key is in the database. A copy of the database alone does not reveal it, but someone with the database and the
  secrets key does, as for provider credentials.
- A change takes up to 10 seconds to reach the other instances.
- Settings in the database are not in the deployment's version control. The audit log records who changed them and
  when.
