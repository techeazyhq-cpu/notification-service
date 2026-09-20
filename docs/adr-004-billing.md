# ADR-004: Billing as a separate module with prepaid holds and monthly invoices

- **Status:** Accepted
- **Date:** 2026-09-20

## Context

The platform needs to charge clients for what they send. Requirements chosen with the product owner: each client is either **postpaid** or **prepaid**; only messages that reach `SENT` are billable; billing produces **invoices** (manual payment recording, CSV export, PDF through the browser's print dialog, no payment gateway); a plan carries a per-channel unit price, a monthly free allowance per channel, an optional platform fee and a tax rate.

The architecture review (`architecture-review.md`) found the existing modules coupled to their frameworks. Billing is new, so it is built as the reference for the dependency rule instead of repeating that.

## Decision

1. **A `billing` module with clean architecture.** `domain` is plain Java (`Money`, `Plan`, `Invoice` state machine, `InvoiceCalculator`, `CreditHold`); `application` holds use cases that depend only on ports (`PlanRepository`, `AccountRepository`, `InvoiceRepository`, `CreditStore`, `UsageReader`, `Transactions`); `infrastructure` holds the JDBC adapters and is wired only in `BillingConfiguration`. ArchUnit tests fail the build if an inner layer imports an outer one.
2. **Money** is a `BigDecimal` at scale 6 with a currency, so sub-cent unit prices work; totals round to 2 decimals half-up once per line. APIs carry money as a string plus currency, never a floating point number.
3. **Postpaid:** usage is derived from `notification_message` (`status = 'SENT'`, by `sent_at`), backed by the partial index `ix_message_sent_usage`; there is no second counter to drift. Invoices are generated per closed UTC month, idempotently (a unique index on `(client_id, period_start)` where status is not `VOID`). A generated invoice is a `DRAFT` and can be regenerated; `issue` assigns `INV-YYYY-NNNNNN` from a database sequence, freezes it and sets the due date. Free allowance and the platform fee apply to postpaid only.
4. **Postpaid spend cap** is soft: at accept time the month's SENT usage plus platform fee plus the cost of the new request is compared to the cap. Messages still in flight are not counted, so a burst can overshoot by what is queued.
5. **Prepaid uses reserve-and-settle.** Accepting a request reserves its worst-case cost with one atomic conditional debit (`UPDATE ... WHERE credit_balance >= :amount`) in the same transaction that stores the request, so a refused request leaves nothing behind and two concurrent requests can never spend the same credit. The `HoldSettlement` job (every 10 seconds, `FOR UPDATE SKIP LOCKED`, safe to run on several instances) charges the messages that were SENT and refunds the rest. Every movement is a `credit_ledger_entry`; the ledger sum always equals the balance. A hold records the unit price it was taken at, so later plan edits do not change what is charged for a request already accepted.
6. **Admin retry** of a failed message takes a MESSAGE-scope hold in one transaction with the status change; without credit the retry is refused and the message stays `FAILED`.
7. **Missing rate means free.** A channel without a price is not charged and not refused.
8. **Errors:** 402 `INSUFFICIENT_CREDIT` / `SPEND_CAP_EXCEEDED`, 403 `ACCOUNT_SUSPENDED`, 404 not found, 409 `INVALID_STATE`, 400 invalid request. Clients see only their own account, usage, ledger and non-draft invoices; another client's invoice is a 404, not a 403.

## Options considered

- **Charge at send time in the dispatcher.** Exact, but the client would only learn of missing credit after acceptance, and a rejected send has already been promised. Reserve-at-accept gives an honest synchronous answer.
- **Charge the full reserved amount and refund later on request.** Simpler, but overcharges failed messages until someone acts. Settlement makes the ledger exact automatically.
- **Counter table for postpaid usage.** Faster reads, but a second source of truth to reconcile. Deferred until measurements justify it.
- **Payment gateway.** Out of scope by decision; payments are recorded manually with an idempotent reference.

## Consequences

Positive: a single, tested place for money rules; prepaid cannot overspend under concurrency (a 60-attempt concurrent test proves it); invoices and the ledger are auditable; the admission check adds one indexed update to the ingest transaction.

Negative / accepted:
- Prepaid reserves worst-case cost, so a large bulk send ties up credit until it settles (about 10 seconds after the last message finishes).
- Editing a plan changes the invoice of any month not yet generated, and regenerating a draft picks up the current plan.
- Prepaid ignores the free allowance and the platform fee.
- The postpaid cap is soft (see 4).
- Invoice search returns at most 200 rows.
- PDF is the browser's print of the invoice page; there is no server-side PDF and no invoice e-mail.
- No payment gateway, dunning, credit notes, or multi-currency conversion.
- A prepaid balance in one currency cannot be re-denominated while it holds credit.

## Follow-ups

E-mail issued invoices; credit notes; low-balance alerts; usage counters if the usage query becomes hot; pagination for invoice search; gateway integration behind a `PaymentGateway` port.
