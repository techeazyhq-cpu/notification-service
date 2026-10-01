# ADR-019: Append-only audit log of administrator actions

- **Status:** Accepted
- **Date:** 2026-10-01

## Context

Administrators can rotate a client's API key, change provider credentials, edit rate limits and billing plans, issue
credit, reprocess dead letters, retry messages, erase personal data, and create or demote other administrators. None
of this left a record of who did it. Sessions showed who had signed in, and the business tables showed the current
state, but not who changed it, when, or what they tried and were refused. The architecture review (finding O2) and
the quality-attribute analysis both list this as a gap, and it blocks any compliance review: an erasure, a credit or
a key rotation has to be attributable to a person.

## Decision

1. **One filter records every state-changing admin API call.** `AuditTrailFilter` handles every `POST`, `PUT`,
   `PATCH` and `DELETE` under `/api/`, after the request has been handled. It records the administrator, their role,
   the method, the endpoint's URI template (such as `/api/admin/clients/{id}/rotate-key`), the actual path, the HTTP
   status, an outcome derived from that status (`SUCCEEDED`, `REJECTED`, `DENIED`, `FAILED`), the source address and
   the user agent. Recording at the HTTP boundary means a new endpoint is audited without anyone remembering to opt
   it in. The alternative, a call in every use case, would need a call in all of them, and one would eventually be
   missed.
2. **It runs inside the security filter chain, right after the session is resolved.** That way it also sees requests
   that security refuses (401 and 403): a viewer trying to rotate a key, or an anonymous caller probing the API.
   Refused attempts are often the most interesting entries. The filter is created in `SecurityConfig` and is
   deliberately not a Spring bean, so it is not also registered as a plain servlet filter outside the chain.
3. **Sign-ins are attributed to the username claimed.** A sign-in has no session yet, so the sign-in endpoint passes
   the submitted username through a request attribute. Failed sign-ins are therefore attributable, which is what
   makes password-guessing visible. An authenticated session always takes precedence over a claimed name.
4. **No request bodies.** Bodies carry passwords, TOTP codes, provider credentials and recipient addresses, so they
   are not stored. The path and URI template identify the resource that was changed, and query strings are dropped
   too. Text values are cut to their column sizes before they are stored, so an oversized header cannot cost the
   record.
5. **Reads are not recorded.** They change nothing, and the admin UI polls several pages every few seconds; recording
   them would bury the changes in noise.
6. **Append-only, enforced by the database.** `admin_audit_event` (changeset `010-admin-audit-log`) has a trigger
   that rejects `UPDATE`, `DELETE` and `TRUNCATE` with an error, whichever role runs them. The runtime role
   `notification_app` has DML rights on every table (ADR-012), so a grant alone could not protect the table without
   the changelog knowing that role's name. The trigger needs no such knowledge, and only the schema owner (the
   migration role) can disable it.
7. **Only `ADMIN` can read it.** `GET /api/admin/audit-events` filters by administrator, outcome and a half-open time
   window (`from` inclusive, `to` exclusive), newest first, paged. The admin UI has an **Audit log** page.
8. **Client addresses are trusted only behind the proxy.** By default (`FORWARD_HEADERS_STRATEGY=none`) the recorded
   source address is the direct peer, which behind the admin UI's nginx is the nginx container. The TLS overlay
   (`docker-compose.tls.yml`), where admin-api is reachable only through Traefik, sets it to `native`: Tomcat then
   takes the caller's address from `X-Forwarded-For`, which Traefik rewrites so a client cannot inject its own.
   `native` is not the default because Tomcat trusts the header from any private-network peer. With Docker port
   publishing, every outside caller arrives from the bridge gateway, a private address. Verified live: with `native`
   and port 8081 published, a forged `X-Forwarded-For: 198.51.100.66` was recorded as the source. Enable it only
   where nothing but the proxy can reach the port.

## Options considered

- **Explicit audit calls in each controller or service.** More context per entry (for example the client's name, or
  the values before and after), but the coverage depends on every endpoint, present and future, remembering to call
  it, and refused requests never reach a controller at all. Rejected as the primary mechanism. It can be added later
  for the few actions where before-and-after values matter (see follow-ups).
- **A `HandlerInterceptor` instead of a filter.** Simpler access to the handler, but it only sees requests that reach
  the dispatcher servlet, so every 401 and 403 would go unrecorded.
- **Write the audit row in the same transaction as the change.** Guarantees that a change and its record commit
  together, but the admin actions span several transaction styles (Spring Data, the billing module's own transaction
  port, Pulsar publishes for reprocessing) and some are not transactional at all. The record would have to be written
  from inside each use case, which brings back the problems of the first option.
- **Fail the request if the audit write fails.** By the time the filter writes, the action has already happened, so
  failing the response would only mislead the caller. Instead the failure is logged and counted in
  `notification.audit.write_failures`, which should alert. The audit table lives in the same database as the data the
  actions change, so a failed audit write almost always comes with a failed action.
- **Ship events to an external log store (SIEM).** The right destination for a large estate, but it adds
  infrastructure this project does not have yet. The database table is the source of truth that such a pipeline can
  read from later.
- **A hash chain for tamper evidence.** Proves that no row was removed even by the schema owner, at the cost of
  serialising writes. Not needed while the trigger already blocks the application role; noted as a follow-up.

## Consequences

Positive: every change made through the admin API, and every refused attempt, now has a who, what, when, outcome and
where; the record cannot be edited or deleted by the application; new endpoints are covered automatically; there is
a page to review it.

Negative / accepted:

- An entry says which resource was changed, not what the old and new values were.
- A crash between the action and the audit insert loses that entry. The metric makes write failures visible, but not
  a process that dies at that exact moment.
- The table grows without bound and holds administrators' usernames and IP addresses, which are personal data about
  staff. Retention has to be decided per deployment, and deleting old entries means the schema owner temporarily
  disabling the trigger.
- Every audited request now does one extra insert. Admin traffic is low, so this does not matter.

## Follow-ups

- Before-and-after values for the most sensitive actions (role changes, provider credentials, which must stay
  masked, and billing credits).
- A retention policy for audit events, with an owner-run purge job.
- Forward events to a SIEM, and alert on `notification.audit.write_failures` and on bursts of `DENIED`.
- Optionally a hash chain over events for tamper evidence against the schema owner.
