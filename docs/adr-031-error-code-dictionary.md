# ADR-031: One error code dictionary for every API error and delivery failure

- **Status:** Accepted
- **Date:** 2026-10-02

## Context

Errors were reported in four different ways:

- **The client API** answered `{code, message}` with about 25 symbolic codes, created as strings where each error
  was raised. One of them, `SENDER_NOT_VERIFIED`, came with HTTP 422 on the send path and 409 when choosing a default
  sender.
- **The admin API** used the same shape in some controllers, Spring's default error JSON (`timestamp, status, error,
  path`) wherever a `ResponseStatusException` was thrown, and an empty body for 401 and most 403s.
- **Delivery failures** were free text in `lastError`, whatever the provider or the code happened to say, with only
  the coarse `failureKind` to group them.
- **Nowhere** listed what a code meant or what to do about it.

So a client could not branch reliably, support had nothing stable to search for, and an operator correlating a
complaint with logs had to start from a timestamp.

## Decision

1. **One catalogue, `ErrorCode`, in notification-core.** It holds every error an API returns and every reason a
   message fails. Each entry has:
   - the symbolic **code** clients branch on, e.g. `RATE_LIMITED`;
   - a numbered **errorId**, e.g. `NS-5001`, whose first digit is the category:
     - 1: the request;
     - 2: access;
     - 3: the resource;
     - 4: billing;
     - 5: capacity;
     - 6: delivery;
     - 8: dependencies;
     - 9: the platform.
   - the HTTP status, or none for delivery outcomes;
   - whether retrying unchanged can succeed;
   - a title, the cause and the resolution, written for whoever meets it.

   Codes are never renamed, renumbered or reused. Tests pin uniqueness, the id format and ranges, complete
   descriptions, and the status of every code that existed before the catalogue.
2. **The response shape is extended, not replaced.**
   - `code` and `message` stay exactly as before.
   - New fields: `errorId`, `category`, `retryable`, `traceId` (the request's trace, ADR-023) and `docs` (a link to
     the code's entry).
   - Both APIs build every error body from the catalogue: exception handlers, the client API key filter, and Spring
     Security's 401 and 403 in the admin API.
   - Framework refusals (unknown path, wrong method or media type) map onto catalogue codes through one shared
     function.
3. **One status per code.** `SENDER_NOT_VERIFIED` is 422 everywhere. Integrations meet it on the send path, which
   keeps its status; only setting a default sender moves from 409 to 422. This is the only status change.
4. **Delivery failures carry a code too.**
   - A new `error_code` column (migration 011) is set by the dispatcher on every failure path:

     | Failure path | Code |
     |---|---|
     | Provider rejected the message | `DELIVERY_REJECTED` |
     | Missing template variable | `TEMPLATE_VARIABLE_MISSING` |
     | Erased or missing content | `MESSAGE_CONTENT_MISSING` |
     | Retries exhausted | `DELIVERY_ATTEMPTS_EXHAUSTED` |
     | Dead-lettered by the broker | `DELIVERY_DEAD_LETTERED` |
     | Retrying after a temporary failure | `PROVIDER_TEMPORARILY_FAILING` |

   - The code is cleared when the message is sent or requeued. Existing rows are backfilled from `failure_kind`.
   - Message views and the CSV export gain `errorCode` and `errorId`; `lastError` keeps the provider's own words.
5. **The dictionary is published two ways from the same source.** `GET /v1/errors` and `GET /v1/errors/{errorId or
   code}` work without an API key, and `docs/error-codes.md` is generated from the enum. A test fails the build if
   the document is stale, and the same test regenerates it when run with `-Derror-catalogue.update=true`.
6. **The consoles show the error id** next to the message, so a user reporting a problem quotes it.

## Options considered

- **RFC 9457 problem details** (`application/problem+json`): the standard shape, but `message` becomes `detail`,
  which breaks every client that reads it. The owner chose compatibility. The extra fields can map onto problem
  details later.
- **Numbers only** (`NS-5001` as the code): stable and short, but code that branches on them is unreadable, and
  existing clients branch on today's names.
- **A code per provider error** (e.g. each SMS gateway's rejection reasons): precise, but unbounded and
  provider-specific. The provider's wording stays in `lastError`, with a stable code beside it.

## Consequences

Positive:

- Clients branch on stable codes, and `retryable` tells them whether to try again.
- Support searches one dictionary by the error id a user quotes.
- The trace id in every error leads straight to the request's trace and log lines.
- Failed messages can be grouped and counted by cause.
- The admin API stops returning three different error shapes.

Negative / accepted:

- **Error bodies are larger** by five fields.
- **One status change** (`SENDER_NOT_VERIFIED` on default-sender selection, 409 to 422).
- **Admin validation errors now name the fields** instead of a generic "fill in all fields".
- **Adding a code is a deliberate change:** the enum, then the regenerated dictionary, with a CI check that they
  agree.

## Follow-ups

- ~~Count failures by `error_code` in metrics.~~ Done: `notification_delivery_errors_total{channel, code, error_id}`
  counts every delivery failure and retry. `NotificationDeliveryRejectionsRising` fires when providers reject over 5%
  of a channel's messages ([slo.md](slo.md#delivery-rejections-rising)).
- OTP validity (ADR-033) adds `OTP_EXPIRED` to the delivery range.
