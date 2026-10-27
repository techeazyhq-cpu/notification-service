# ADR-051: A grace period for the previous signing secret

- **Status:** Accepted
- **Date:** 2026-10-27
- **Amends:** ADR-036

## Context

ADR-036 made rotation of a client's request-signing secret immediate. Once the administrator issues a new secret, the
client API refuses every request signed with the old one, within 30 seconds at most. A client that signs every write
can therefore only rotate in one of two ways:

- agree an exact moment with the operator and switch its configuration at that moment;
- stop signing, have signing made optional, take the new secret, start signing again and have signing required again.

The first loses requests if the moment is missed, for example on a fleet of servers that cannot all switch at once.
The second leaves the client unprotected while it lasts. ADR-036 left "accepting the previous secret for a grace period"
for a later change.

## Decision

1. **A rotation can keep the old secret working for a while.** `POST /api/admin/clients/{id}/signing-secret` takes an
   optional body `{"previousSecretHours": n}`, with `n` from 0 to 168 (7 days).
   - With `n` above 0, the secret being replaced becomes the client's previous secret until now plus `n` hours.
   - With `n` = 0, or with no body, nothing is kept: the existing API's behaviour is unchanged.
   - Only one previous secret is kept. A second rotation drops the secret kept by the first, whatever its grace period.
   - Issuing a client's first secret keeps nothing, whatever `n` is.
2. **The client API accepts either secret during the grace period.** It checks the current secret first, then the
   previous one if its period has not ended.
   - The end is compared with the clock on every request, so the 30-second client cache cannot stretch it.
   - Freshness, single use of the nonce and the body limit apply to both secrets alike. A request signed with the
     previous secret can no more be replayed than one signed with the current one.
3. **Storage.** Migration 030 adds `client.previous_signing_secret` and `client.previous_signing_secret_expires_at`.
   - The previous secret stays encrypted as it was, under the credentials key (ADR-036, ADR-037). The re-encryption
     job of ADR-037 rewrites it like the current one.
   - A check constraint keeps the two columns both set or both empty.
4. **Ending it.**
   - `DELETE /api/admin/clients/{id}/signing-secret/previous` ends the grace period at once, for example when the client
     has switched, or when the old secret may have leaked.
   - Removing the signing secret removes the previous one too.
   - Every ten minutes the dispatcher deletes previous secrets whose period has ended, so a secret nobody may use does
     not stay in the database. The client API refuses them from the moment the period ends, whether or not they have
     been deleted yet.
5. **The admin console** asks how long the old secret keeps working when an administrator rotates. The choices are
   none, 1 hour, 24 hours (the default) and 7 days. While a grace period runs, the client's row shows when the old
   secret stops working, with an **End now** button.

## Options considered

- **Keep rotation immediate and document the stop-and-restart routine.** Nothing to build, but the client is
  unprotected during the change, which is exactly when an operator is handing secrets around.
- **Let a client hold several active secrets, each with its own label and expiry.** More general, but it needs a table,
  key identifiers in the signature headers, and a console to manage them. Two secrets for a bounded period covers
  rotation, which is the only need we have seen.
- **Let the client choose the secret with a key-id header.** The service would not have to try two HMACs. But a second
  HMAC costs microseconds, and a new header changes the signing format every tenant has implemented.
- **Make the grace period the default for every rotation.** Rotation after a suspected leak must cut the old secret off
  at once. An explicit choice, with 0 as the API's default, keeps that the safe path. The console defaults to 24 hours,
  where an administrator sees the choice.

## Consequences

- A client can switch its signers one by one within the grace period, without losing requests and without turning
  signing off.
- During the grace period, a leaked old secret still works. Rotation after a leak must use 0 hours, or end the period
  with **End now**. The console's confirmation and the onboarding runbook say so.
- A signed request that fails verification costs up to two HMACs instead of one, but only while a grace period runs.
- One more admin endpoint, two columns and a small dispatcher job to maintain.
