# ADR-002: Client-owned templates, with the content snapshotted on the request

- **Status:** Accepted
- **Date:** 2026-09-20

## Context

Templates were global and managed by administrators, and the dispatcher looked the template up at send time. Clients need different content for different purposes and should not depend on an administrator to change it. Letting tenants edit templates that are read later at send time has two problems: an edit can change (or break) a bulk that is already queued, and a delete would fail every remaining message.

## Decision

1. **Ownership.** `template.client_id` is nullable: `NULL` means shared (admin-managed, visible to everyone, read-only for clients); otherwise the template belongs to that client. Names are unique per owner (partial unique indexes). A client cannot create a name held by a shared template; a client's own template takes precedence when resolving `templateName`.
2. **Snapshot at accept time.** The request stores the resolved subject and body (the columns already existed for inline content). The dispatcher renders only from the request. `notification_request.template_id` is a reference for support and audit; its foreign key is `ON DELETE SET NULL`. A data migration backfilled the content of existing requests that pointed at a template.
3. **Fail fast on variables.** Missing variables are validated for every recipient at submit and reject the whole request, instead of failing message by message later.
4. **Self-service surface** in the Client API and client UI (CRUD, preview, variable listing); admins keep managing shared templates and can see (not change) client-owned ones.

## Options considered

- **Template versions** (immutable `template_version` rows referenced by requests): gives a per-template history and diffing, but adds a table, a join at send time and version management for every caller. The snapshot gives the same in-flight safety with less machinery; a history can be added later without changing the dispatcher.
- **Block edits and deletes while a request references the template:** simple, but frustrating (a busy client could never edit) and it needs a reference count on the hot path.
- **Keep templates global and add an approval workflow:** keeps content control but does not remove the dependency on administrators.

## Consequences

Positive: no interaction between template management and in-flight sends; deletes are always safe; the dispatcher gets simpler (no template lookup, one fewer query per message); clients get errors when they submit, not after.

Negative / accepted: the template text is stored once per request (small, one row per request not per message); there is no per-template edit history yet; a client can author arbitrary content (as it could already with inline `body`), so content policy is the caller's responsibility, not enforced here; the browser-held API key now also allows template changes (see the design doc's note on read-only credentials).

## Follow-ups

Template version history if clients need it; optional content-policy checks (length limits per channel, blocked terms) if clients are less trusted than internal applications; audit log of template changes.
