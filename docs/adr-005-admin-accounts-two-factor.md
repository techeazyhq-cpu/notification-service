# ADR-005: Database-backed administrators with password change and TOTP two-factor authentication

- **Status:** Accepted
- **Date:** 2026-09-20

## Context

The Admin API accepted HTTP Basic for one user whose name and password came from configuration. That meant the password could not be changed without redeploying, there was no second factor, the password travelled on every request, and nothing limited guessing. The product owner asked for a password change option with two-factor authentication.

## Decision

1. **Accounts in the database** (`admin_user`, `admin_recovery_code`, `admin_session`, migration 005). `ADMIN_USERNAME` and `ADMIN_PASSWORD` only seed the first administrator when the table is empty; afterwards changing them has no effect.
2. **Sign-in is a login call, not Basic.** `POST /api/admin/auth/login` returns a random session token that the UI sends as `Authorization: Bearer`. Only the SHA-256 of the token is stored. Sessions expire after 30 minutes idle and 12 hours in total (`admin.session-idle-minutes`, `admin.session-max-hours`), and can be revoked. HTTP Basic is no longer accepted, because it would let a password alone bypass the second factor.
3. **Password change** needs the current password, is length-based (12 to 128 characters, not containing the user name, not trivially repetitive), and signs out every other session.
4. **Two-factor authentication is TOTP** (RFC 6238: HMAC-SHA1, 30 seconds, 6 digits), which every authenticator app supports. Setup shows a QR code and the key, and is only switched on after the user proves the app works by entering a code. A code is accepted for the current 30-second step and one step either side, and each step can be used once (the last accepted step is advanced by a single conditional update, so two simultaneous logins cannot both use one code).
5. **Recovery codes.** Turning two-factor on returns 10 one-time codes, shown once and stored only as hashes. Any one of them can be entered instead of a code, and they can be regenerated.
6. **Sensitive changes need re-authentication.** Turning two-factor off or generating new recovery codes needs the password and a valid code; changing the password needs the current one.
7. **Secrets at rest.** The TOTP secret is encrypted with AES-256-GCM using a key derived from `admin.two-factor-key` (`ADMIN_TWO_FACTOR_KEY`), so a database copy alone cannot produce codes. The service logs a warning while the development default is in use.
8. **Guessing limits.** After 5 wrong passwords or codes (`admin.max-failed-attempts`) an account is locked for 15 minutes (`admin.lockout-minutes`); asking for the code is not a failure. Unknown users and wrong passwords give the same answer and take similar time.
9. **Stable error codes:** `INVALID_CREDENTIALS`, `OTP_REQUIRED` (401), `ACCOUNT_LOCKED` (429), `REAUTHENTICATION_FAILED` (403, so the UI does not treat it as a lost session), `INVALID_REQUEST`, `INVALID_STATE`.

## Options considered

- **Keep Basic and add a code header.** Smallest change, but passwords keep travelling on every request and scripts can skip the code. Rejected.
- **JWT.** No database lookup per request, but no revocation, which is needed for "sign out other sessions". Opaque tokens with a hashed lookup were chosen; the cost is one indexed read per request.
- **OIDC / SSO now.** The right answer for many administrators, and still the recommended target. It is a larger integration and this change works without an identity provider. Local accounts can stay as a break-glass option later.
- **SMS or e-mail codes.** Weaker (SIM swap, mailbox compromise) and depend on our own delivery channels. Not chosen.

## Consequences

Positive: password can be changed in the UI; optional second factor with recovery; short revocable sessions; brute force is slowed; the password no longer accompanies every call.

Negative / accepted:
- There is still only one administrator account and no UI to create more, and no roles. Adding users and roles is the next step, ideally through OIDC.
- Lockout is per account, so someone who knows the user name can keep the account locked (a denial of service); there is no per-IP limit yet.
- If the only device is lost and the recovery codes are gone, recovery needs database access (delete the row to re-seed from configuration, or clear the two-factor columns).
- Changing `ADMIN_TWO_FACTOR_KEY` makes stored secrets unreadable, so two-factor must be reset.
- Tokens live in `sessionStorage`, so a cross-site scripting flaw in the admin UI could read one. The UI has no user-supplied HTML rendering, but this is the same exposure as before.
- Scripts must sign in first (`scripts/seed.mjs` does, and reads `ADMIN_OTP` if two-factor is on).

## Follow-ups

Multiple administrators with roles and an audit log of admin actions; OIDC sign-in; per-IP rate limiting on login; e-mail notice on password or two-factor changes; the same two-factor option for client portal sign-in once it has user accounts.
