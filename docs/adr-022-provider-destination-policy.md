# ADR-022: Provider destination policy against server-side request forgery

- **Status:** Accepted
- **Date:** 2026-10-02

## Context

An HTTP gateway provider posts each message, with its recipient and any configured `authHeader`, to whatever `url`
its settings hold; an SMTP provider connects to whatever `host` and `port` its settings hold. Both are edited by
administrators. Nothing checked those destinations, so anyone able to edit a provider could make the dispatcher, and
client-api through sender confirmation e-mails, send requests into the platform's own network: PostgreSQL, Redis,
the Pulsar admin API, the actuator ports, or cloud instance metadata at `169.254.169.254`, which hands out credentials.
That makes one stolen administrator session a pivot into the network. The architecture review listed this as
finding S7, and the 2026-10-01 re-review as production blocker 7.

## Decision

1. **One policy, `ProviderDestinationPolicy` in notification-core, enforced at every point that uses a destination:**
   admin-api when a provider is created or updated, which answers `400 PROVIDER_DESTINATION_REFUSED` with the reason
   and saves nothing; the dispatcher before every send; and client-api before a sender confirmation e-mail.
2. **Trusted hosts are allowed as they are.** `notification.provider-egress.trusted-hosts` (`PROVIDER_TRUSTED_HOSTS`)
   lists exact host names, or `*.domain` for subdomains (not the domain itself), compared case-insensitively and
   ignoring a trailing dot. A trusted host may be internal and may use plain HTTP: that is how an operator allows an
   on-premises gateway or relay, and how `docker compose` allows `mailpit` and `catcher`.
3. **Every other host must be public.** It must be allowed in general
   (`PROVIDER_OTHER_PUBLIC_HOSTS_ALLOWED`, default `true`; `false` means trusted hosts only). An HTTP gateway must use
   HTTPS. Every address the host resolves to must be outside the non-public blocks: `0/8`, `10/8`, `100.64/10`
   (carrier-grade NAT), `127/8`, `169.254/16`, `172.16/12`, `192.0.0/24`, `192.168/16`, `198.18/15`, `224/4`, `240/4`,
   and in IPv6 `::`, `::1`, `fc00::/7`, `fe80::/10`, `ff00::/8`. IPv4-mapped IPv6 addresses are checked as IPv4. One
   non-public address among several is enough to refuse, and a host that does not resolve is refused.
4. **Malformed destinations are refused:** schemes other than `http` and `https`, URLs without a host, and URLs with
   credentials in them (`https://user:pass@host`, which also hides the real host from a casual reader). Credentials
   belong in `authHeader`, which is encrypted at rest (ADR-013).
5. **At send time a refusal is a transient failure of that provider.** It counts against the provider's circuit
   breaker, the next provider of the channel is tried, and the message is retried later rather than failed. A
   misconfigured provider must not destroy messages that a corrected configuration would deliver.
6. **Redirects are never followed** by the HTTP gateway client (the JDK client's default, now pinned by a test).
   Otherwise an allowed public gateway could answer `302 Location: http://169.254.169.254/...` and get past the check
   on the configured URL. A 3xx answer fails the send permanently.

## Options considered

- **Only a network egress policy (Kubernetes NetworkPolicy, firewall).** The strongest control and still recommended,
  but it lives outside this repository, `docker compose` has none, and it cannot explain to an administrator why a
  provider was rejected. The application check is defence in depth and gives an immediate, specific answer.
- **An allow-list only, with no public default.** Safest, but every deployment would have to list every SaaS gateway
  before sending anything. It is available as `PROVIDER_OTHER_PUBLIC_HOSTS_ALLOWED=false`. The default blocks the
  internal network, which is where the damage is.
- **Check only when a provider is saved.** DNS can change after the check, and providers stored before this change
  were never checked, so the dispatcher checks again before every call.
- **Refuse at send time as a permanent failure.** Would fail every queued message for a configuration mistake that an
  administrator can fix in a minute.

## Consequences

Positive: a stolen administrator session can no longer turn providers into a probe or proxy for the internal network
or cloud metadata. Rejections at save time say exactly why. Existing providers that point inward stop being used, and
their messages wait instead of failing.

Negative / accepted:

- **DNS rebinding is narrowed, not closed.** The policy resolves the host, then the HTTP or SMTP client resolves it
  again when it connects. A hostile DNS server answering with a public address first and an internal one second can
  slip between the two lookups. Closing this needs connections pinned to the checked address, or an egress firewall,
  which is the recommended production control anyway.
- **Each send now does a DNS lookup.** It is cached by the JVM and the operating system, and small next to the
  provider call.
- **Deployments with internal gateways must list them** in `PROVIDER_TRUSTED_HOSTS` on admin-api, client-api and the
  dispatcher. Until they do, those providers are refused at save time and skipped at send time.

## Follow-ups

- Egress network policy in the deployment manifests (with blocker 5, deployment artefacts).
- Optionally pin the HTTP client's connection to the address the policy checked.
