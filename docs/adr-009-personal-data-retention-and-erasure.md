# ADR-009: Retention and erasure of personal data

- **Status:** Accepted
- **Date:** 2026-09-20

## Context

The platform stores the recipient address of every message, per-recipient template variables, provider error text (which can quote an address) and, for inline content, message bodies that may name the recipient. Nothing was ever removed, so the data grew without limit and could not be erased for a person who asked. The product review rated this a blocker for GDPR and DPDP style obligations (storage limitation and the right to erasure).

## Decision

1. **Retention runs daily in the dispatcher** (`retention.cron`, default 02:30) and works in steps, each configurable and off when 0:
   - `personal-data-days` (90): a finished message (SENT or FAILED) loses its recipient, template variables and error text, which are replaced by `[erased]` / `{}` / null; a request whose messages are all finished loses its subject and body. Status, channel, timestamps, attempts and the provider message id stay, so counts, invoices and support history still work.
   - `delete-days` (400): finished message rows, and requests left without messages, are deleted. Billing does not need them any longer: invoices keep their own lines and prepaid holds settle within seconds.
   - `idempotency-days` (7): idempotency keys are cleared, so they stop being stored and stop blocking a reused key.
2. **Erasure on request.** `POST /v1/privacy/erasure {recipient}` (client, scoped to the caller) and `POST /api/admin/privacy/erasure {recipient, clientId?}` (operator, one client or all) erase every finished message to that recipient, matched case-insensitively, and the content of single-recipient requests that only went to them. Messages still being delivered are left and reported as `inFlightMessages`; the caller repeats the request later. Repeating it changes nothing.
3. **Never touch work in progress.** Only SENT and FAILED messages are erased or deleted. An erased message can no longer be retried: the admin retry refuses it (409), so an erased placeholder is never sent to.
4. **Cheap at scale.** Partial indexes (`ix_message_erase_due`, `ix_request_erase_due`, `ix_request_idempotency_due`, migration 007) contain only rows that are due. Each statement changes at most `batch-size` (5000) rows, so transactions stay short; a run can be repeated or overlap with another instance because every statement only selects rows that are still due.
5. **Visible.** Counters `notification.retention{action}` per step, a log line per run, the policy at `GET /v1/privacy/retention` and in the admin and client UIs (a Privacy page each; the client one has the erase form).

## Options considered

- **Delete everything at 90 days.** Simplest, but destroys the usage records that postpaid invoices and disputes rely on. Erasing the personal fields and deleting later keeps the counts.
- **Encrypt recipients and destroy keys (crypto-shredding).** Elegant for backups, but adds key management and makes searching by recipient (a feature of the tracking UI) harder. A candidate later.
- **Hash the recipient instead of a placeholder.** Would keep a pseudonymous link, which is still personal data for a small address space (phone numbers are guessable). Rejected.
- **A queue table for erasure requests.** Not needed: an erasure is one indexed-by-client statement; large clients may take a few seconds.

## Consequences

Positive: data has a bounded life, erasure is possible and verifiable, and the retention behaviour is tested against real PostgreSQL (seven scenarios, including batching, in-flight protection and idempotency) and was run live: a recipient was erased on request, and aged rows were erased and their idempotency keys cleared by the scheduled job.

Negative / accepted:
- **Backups and logs are outside this change.** Erased data remains in database backups until they expire, and logs must not contain recipients (the erasure log line records only counts).
- **Other stores:** the broker holds message ids only (no recipient), but the provider (SMTP server, SMS gateway) keeps its own copy under its own policy.
- **Erasure by recipient scans one client's messages** (no index on recipient, deliberately, to keep the ingest path fast). Fine for the rare request; for very large clients it can take seconds.
- **Free-text fields:** `client_reference` and inline content in bulk requests are not erased by recipient. Clients should not put personal data in `clientReference`.
- Defaults (90 and 400 days) are a starting point; the legal retention period is the operator's decision (confirm with your DPO).
- The status views show `[erased]` in place of the address once data is erased.

## Follow-ups

Erase or expire audit and provider data if any is added; per-client retention overrides; a "prove erasure" report; crypto-shredding; retention for admin sessions and unconfirmed sender addresses.
