# ADR-015: Role-based access for administrators (viewer, operator, admin)

- **Status:** Accepted
- **Date:** 2026-09-26

## Context

Every administrator session carried exactly one authority, `ROLE_ADMIN`, and the security filter chain granted it
blanket access to all of `/api/**`. There was no way to give someone read-only access to dashboards and message
status, or day-to-day operational access (retrying a failed message, reprocessing dead letters) without also handing
them the ability to rotate a client's API key, change provider credentials, or edit billing plans. This was finding
S1's remaining gap after ADR-005 (password + TOTP + lockout closed the "static admin/admin" problem, but not the
"one administrator, one undifferentiated level of access" problem) and the design document's own next step (OIDC
with roles: viewer/operator/admin).

## Decision

1. **Three roles:** `VIEWER`, `OPERATOR`, `ADMIN`, stored as a column on `admin_user` (migration `009-admin-roles`,
   existing administrators default to `ADMIN` so nobody loses access on upgrade). Each role includes what the one
   below it can do: an `ADMIN` session is granted `ROLE_ADMIN`, `ROLE_OPERATOR` and `ROLE_VIEWER`; `OPERATOR` gets
   `ROLE_OPERATOR` and `ROLE_VIEWER`; `VIEWER` gets only `ROLE_VIEWER` (`AdminRole.impliedRoleNames()`). This is
   plain multi-authority assignment at authentication time, not Spring Security's `RoleHierarchy` bean — simpler to
   read, and trivial to unit test without standing up a security context.
2. **The boundary drawn:**
   - `VIEWER`: every `GET` under `/api/**` (dashboard, clients, templates, providers, rate limits, messages, dead
     letters, billing, privacy) plus managing their own account (password, two-factor, sign out).
   - `OPERATOR`: everything `VIEWER` can, plus the day-to-day recovery actions that don't change configuration:
     reprocessing dead letters, retrying a message, handling an erasure request, and topping up or adjusting a
     prepaid client's credit (a routine support action, not a plan/pricing change).
   - `ADMIN`: everything, including client/API-key management, provider credentials, rate-limit policies, shared
     templates, billing plans/accounts/invoices, and managing other administrators.
   This line is a judgement call, not a fixed standard — it is deliberately drawn in one place
   (`SecurityConfig`'s `authorizeHttpRequests`) as a short, readable list of request matchers, so moving a specific
   endpoint between `OPERATOR` and `ADMIN` later is a one-line change, not a redesign.
3. **A role's authority is resolved fresh on every request**, not carried on the session token: `authenticate()`
   looks up the administrator's current role from the store each time. A role change (or account removal) takes
   effect on the administrator's very next request, not only after they sign in again.
4. **Managing other administrators** (list, create, change role, remove) is a new endpoint,
   `/api/admin/administrators`, always requiring `ADMIN` regardless of HTTP method. Without this, roles other than
   the bootstrap administrator's could never actually be created. Two guards prevent a lockout: an administrator
   cannot delete their own account, and the last remaining `ADMIN` can neither be demoted nor deleted.

## Options considered

- **Spring Security's `RoleHierarchy` bean**, declaring `ADMIN > OPERATOR > VIEWER` once instead of assigning
  multiple authorities per session. More idiomatic Spring, but `authorizeHttpRequests()`'s `hasRole(...)` does not
  pick up a `RoleHierarchy` bean automatically in Spring Security 6 without extra wiring of the authorization
  manager itself — a subtler, more version-sensitive setup for the same outcome. Revisit if the role list grows
  enough that hand-listing implied roles becomes unwieldy.
- **Method-level `@PreAuthorize` per controller method** instead of centralizing rules in `SecurityConfig`. Rejected
  for now: the current approach keeps every endpoint's required role in one file, readable as a single table, which
  matters more here than the small win of colocating the annotation with its method.
- **A fourth role for billing specifically**, since it's the most financially sensitive area. Rejected as
  premature: nothing in this codebase's billing model asks for a role scoped that narrowly yet, and a fourth role
  most administrators would never assign is a UI and mental-model cost paid by everyone for a need nobody has
  expressed. `OPERATOR` covers the routine billing action (credit top-up); everything else billing-related is
  `ADMIN`, matching how the rest of platform configuration is treated.

## Consequences

Positive: a support engineer can be given exactly enough access to retry failed messages and reprocess dead letters
without also being able to rotate every client's API key; a stakeholder who only needs dashboards and status can get
a `VIEWER` account instead of the shared admin password; closes the RBAC half of finding S1.

Negative / accepted: this is still database-backed accounts, not the OIDC/SSO integration the design document names
as the eventual production answer — that remains a separate, larger piece of work (`admin.two-factor-key`/
`ADMIN_TWO_FACTOR_KEY` and this role model would both need to map onto whatever an OIDC provider asserts); the admin
UI (`admin-ui`) does not yet have a page for managing other administrators or roles — the API exists and is tested,
but using it today means calling `/api/admin/administrators` directly (`curl` or the Swagger-equivalent), not
through the SPA. A UI for it is a natural, low-risk follow-up that doesn't change anything decided here.

## Follow-ups

An `admin-ui` page for listing, creating, and role-managing administrators; OIDC/SSO mapping external roles onto
these three; consider whether the `OPERATOR` boundary needs to move once real usage shows which actions support
staff actually need day to day.
