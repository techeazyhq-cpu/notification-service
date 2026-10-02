# ADR-020: Enforce the initial-password change and two-factor authentication on the server

- **Status:** Accepted
- **Date:** 2026-10-02

## Context

ADR-005 gave administrators passwords, optional TOTP two-factor authentication and an "initial password" flag, but
nothing acted on that flag except a banner in the admin UI. An administrator created by the bootstrap
(`ADMIN_USERNAME`/`ADMIN_PASSWORD`) or by another administrator could use the whole admin API, including rotating
API keys, changing provider credentials, issuing credit and reading recipients, without ever replacing the password
someone else chose and without a second factor. Any client that skipped the banner (a script, curl, a modified UI)
was not affected by it at all. The 2026-10-01 re-review listed this as a production blocker.

## Decision

1. **Two account setup steps, enforced per request.** `CHANGE_PASSWORD` is pending while the account still has the
   password it was created with. `ENABLE_TWO_FACTOR` is pending while TOTP is off. Each is mandatory when its
   property is true: `admin.require-password-change` (`ADMIN_REQUIRE_PASSWORD_CHANGE`) and
   `admin.require-two-factor` (`ADMIN_REQUIRE_TWO_FACTOR`), both `true` by default and for every role. Viewers can
   read recipients and message content, so a viewer account is worth protecting too. The pending steps are worked
   out from the stored account on every request, so finishing a step takes effect on the next call without signing
   in again.
2. **A setup-pending session gets one role, `ACCOUNT_SETUP`, instead of its administrator role.** That role is
   allowed only `/api/admin/auth/**`: its own account, the password change, two-factor setup, recovery codes and sign
   out. Everything else, reads included, is refused with `403` and
   `{"code":"ACCOUNT_SETUP_REQUIRED","message":"..."}` from `AccountSetupAccessDeniedHandler`. Other refusals keep
   their previous plain `403`. The rule lives in the security filter chain, so it covers every endpoint, present and
   future, and every client, not just the admin UI.
3. **Sign-in and `GET /api/admin/auth/me` list the pending steps** (`pendingSetup`). The admin UI sends the
   administrator to **My account** after sign-in and whenever an API call answers `ACCOUNT_SETUP_REQUIRED`, and shows
   a checklist of what is left.
4. **Two-factor cannot be turned off while it is required.** The request is refused before any credential is
   checked, so it does not count towards lockout. The UI hides the button.
5. **Relaxing either rule is for local development only.** A service refuses to start with either property set to
   `false` unless the `local` profile is active, the same guard ADR-013 applies to the shipped default secrets.
   `docker-compose.yml` sets both to `false` (overridable from the shell), so the local stack, `scripts/seed.mjs` and
   the README's curl examples keep working with `admin`/`admin`.

## Options considered

- **Enforce only in the admin UI.** That is what existed; it protects nothing from a direct API call.
- **Refuse sign-in until setup is done.** Leaves no way to complete setup, because changing the password and
  enrolling TOTP both need a session.
- **Return a separate, restricted token type at sign-in.** Works, but the restriction then lasts for the token's whole
  life. The per-request check lifts the restriction as soon as the steps are done, on the same session, and keeps a
  single token type.
- **Require two-factor only for `OPERATOR` and `ADMIN`.** Simpler for read-only staff, but viewers see personal data.
  If a deployment wants this later, it is a change to `pendingSetup`, not to the enforcement.
- **Tie the relaxation to the `local` profile alone, with no properties.** Hides the behaviour inside a profile name.
  Explicit properties make it visible in configuration, and the guard still stops them being used outside `local`.

## Consequences

Positive: an account created with someone else's password, or without a second factor, can no longer change or read
anything until it has been secured, whatever client it uses. The same check covers every current and future endpoint.

Negative / accepted:

- Every new administrator, and the bootstrap administrator of every new deployment, has to complete setup before
  doing anything else. That is the intent.
- Scripts that sign in as an administrator need a completed account and a current TOTP code (`ADMIN_OTP` in
  `scripts/seed.mjs`) outside local development.
- The audit log (ADR-019) records a setup-pending session's actions without a role, because the session does not
  carry its administrator role until setup is done.
- As before (ADR-005), there is no self-service recovery for an administrator who loses their authenticator and all
  recovery codes. Another `ADMIN` has to delete and recreate the account.

## Follow-ups

- An `ADMIN`-initiated "reset two-factor" for another administrator, which would also force a password change.
- OIDC single sign-on (ADR-001's intended end state), with the identity provider enforcing MFA instead.
