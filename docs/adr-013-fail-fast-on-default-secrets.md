# ADR-013: Fail fast on shipped default secrets, and encrypt provider secrets at rest

- **Status:** Accepted
- **Date:** 2026-09-25

## Context

Every credential this project ships as a working-out-of-the-box default — the database password, the admin
password, the admin two-factor encryption key — was only ever a `docker compose`-friendly value (`notification`,
`admin`, `development-only-change-me`), never something meant to reach a shared environment. Nothing stopped it from
doing so anyway: at most, one of them (`admin.two-factor-key`) logged a warning; the rest were silent (findings S3
and, for provider credentials specifically, S4 in `docs/architecture-review.md`). Separately, `provider_config.settings`
stores SMTP passwords and gateway auth headers as plaintext JSON, masked only when read back through the admin API —
a database dump or backup hands over live credentials.

## Decision

1. **`InsecureDefaults` (notification-core)** refuses to start a service if a checked property still equals a value
   this project ships as a development default, unless the `local` Spring profile is active. `local` is opt-in —
   nothing sets it automatically — so the default posture is fail-fast; `docker compose`'s own services set it,
   which is what keeps the reference stack working unchanged. Checked: `spring.datasource.password` (`notification`
   *and* `notification_app`, covering both role-separation states from ADR-012), `admin.two-factor-key`, and
   `admin.password` — the last one only at the moment an administrator is actually about to be created from it (an
   empty user store), not on every restart, since the environment variable is inert afterwards and an operator who
   already changed the password through the UI should not be blocked by an unrelated leftover env var. `db-migration`
   does not depend on `notification-core` by design (see its `pom.xml`), so it carries its own equivalent, smaller
   check for its own default password.
2. **`ProviderSecrets` (notification-core)** encrypts the secret-looking settings of a provider config (`password`,
   `authHeader`, `apiKey`, `token`, `secret` — the same set the admin API already masks on read) with AES-256-GCM
   before they reach the database, keyed by `notification.secrets-key` (env `SECRETS_ENCRYPTION_KEY`), which is
   itself subject to the same `InsecureDefaults` check. `admin-api`'s `ProvidersController` encrypts on save;
   `dispatcher`'s `ProviderRegistry` and `client-api`'s `SmtpVerificationMailer` decrypt right before they use a
   setting to actually connect, so `SmtpProvider` and `HttpJsonProvider` never change: they still just read a plain
   `Map<String, String>`.
3. **Backward compatible by construction, not by migration.** An encrypted value is stored with an `enc:` prefix;
   anything without it is treated as already-plaintext. An existing deployment's stored secrets keep working
   unchanged and are encrypted the next time each provider config is saved — no forced backfill, no downtime, no
   changelog entry needed.

## Options considered

- **A secrets manager (Vault, a cloud KMS) instead of an application-level key.** The right answer for a real
  production deployment, and still an open follow-up (see docs/architecture-review.md's roadmap), but it ties the
  open-source default to infrastructure most people trying this project locally, or even in a first real deployment,
  don't have. `SECRETS_ENCRYPTION_KEY` as a plain environment variable is what works identically everywhere; nothing
  here prevents sourcing that variable from a real secrets manager later.
- **Warn instead of refuse to start.** The status quo for `admin.two-factor-key`, and it did not work: the value
  shipped anyway. A warning that nobody is paged on is not a control.
- **Check every property unconditionally, including the admin password on every restart.** Simpler, but creates a
  permanent, unfixable-without-code-change nag for an operator who rotated the password through the UI and left the
  bootstrap env var in place — a real operational cost for a false signal, not a security gap (see decision 1).

## Consequences

Positive: a service can no longer end up running against a shipped credential without an explicit, visible choice
(`SPRING_PROFILES_ACTIVE=local`) to allow it; a database backup or an admin session compromise no longer hands over
usable SMTP/gateway credentials directly; both changes required no schema migration and no forced re-entry of
existing configuration.

Negative / accepted: one more environment variable (`SECRETS_ENCRYPTION_KEY`) to provision and back up per
environment — losing it makes every encrypted provider secret unreadable, the same trade-off ADR-005 already made
for `ADMIN_TWO_FACTOR_KEY`; `InsecureDefaults` only catches the *known, shipped* default value, not "any weak
password" — it is a tripwire for this project's own defaults leaking into production, not a password-strength
policy; the `admin.password` check is skipped on every restart after the first administrator exists, so a
deliberately-reset-to-`admin` password later is not caught by this mechanism (the account lockout and audit
follow-ups already tracked for admin auth are the right place for that, not this check).

## Follow-ups

Real secrets-manager integration (Vault, cloud KMS) as the production-grade replacement for a plain environment
variable; a one-time admin-triggered "re-save all providers" action to finish encrypting any secret an operator
never happens to touch again; extending the same `InsecureDefaults` pattern to any future service-level credential.
