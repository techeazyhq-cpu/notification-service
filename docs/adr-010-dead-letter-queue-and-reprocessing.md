# ADR-010: Dead-letter queue and reprocessing of failed messages

- **Status:** Accepted
- **Date:** 2026-09-20

## Context

Delivery already retried transient failures with backoff and ended a message as `FAILED` after the last attempt or on a permanent error, and Pulsar was configured with a dead-letter topic per channel. But there was no way to work with what had failed:

- the broker's dead-letter topics (`notification-<channel>-dlq`) had no reader, so a message dead-lettered by the broker stayed half-finished in the database and was never seen by an operator;
- a `FAILED` message carried no reason category, so an operator could not tell an outage victim (worth resending) from a bad phone number (resending is pointless);
- the only remedy was to retry one message at a time through the API.

## Decision

1. **A dead letter is a message that ended `FAILED`**, with a kind in `failure_kind`:
   - `PERMANENT`: the provider or the content rejected it (invalid recipient, missing variable). Resending fails the same way until something is fixed.
   - `EXHAUSTED`: every attempt failed for a temporary reason (outage, timeouts).
   - `DEAD_LETTERED`: the broker gave up delivering it to a worker.
   Migration 008 adds `failure_kind` and `reprocess_count`, backfills existing failures (`Gave up after...` is `EXHAUSTED`, the rest `PERMANENT`), and adds a partial index over `FAILED` rows, so the queue view stays cheap however many messages exist.
2. **Record the broker's dead letters.** The dispatcher now subscribes to every channel's dead-letter topic (`dead-letter-recorder`) and moves the named message to `FAILED / DEAD_LETTERED`, but only if it is still in flight, so a message that was delivered or already failed is never overwritten. A dead letter that cannot be read is logged and dropped (redelivering it forever would help nobody); a database failure makes the broker deliver it again. A counter `notification.dead_letter{channel}` counts them.
3. **Reprocess in bulk.** `POST /api/admin/dead-letters/reprocess` takes either explicit ids, or filters (client, channel, kind, error text) and a limit (default 200, at most 1000, oldest first). Each message goes through the same path as the single retry (`MessageRetry`): one transaction per message that reserves credit for prepaid clients, refuses erased messages (ADR-009) and messages that changed meanwhile, resets attempts, clears the failure kind and counts the reprocess. Refusals are reported per message and never stop the others. The re-queued messages are then published; any that fail to publish stay in the outbox state and the sweeper sends them. Messages that failed `PERMANENT` are skipped by filtered runs unless asked for; naming them explicitly is allowed.
4. **See the queue.** `GET /api/admin/dead-letters` (filters and paging), `GET /api/admin/dead-letters/summary` (counts by kind, channel and client, the most common error texts, the oldest failure), and a **Dead letters** page in the admin UI with the summary, filters, selection, "reprocess selected" and "reprocess up to 200 matching" with confirmation, and the list of refusals.

## Options considered

- **Move messages between broker topics by hand (`pulsar-admin`) to reprocess.** Loses the link to billing, erasure and audit (who reprocessed what, how often), and needs broker access. Rejected: the database record is the source of truth and the broker only carries ids.
- **Automatic redrive when a provider recovers.** Attractive, but it would spend prepaid credit and resend messages without a human deciding; a wrong assumption about "recovered" repeats an outage. Left as a follow-up behind an explicit switch.
- **A separate dead-letter table.** Duplicates the data. The status and the kind on the message row, plus a partial index, give the same queue without moving anything.

## Consequences

Positive: failures are visible, classified and fixable in bulk; the broker's own dead letters stop disappearing; reprocessing is safe with billing and erasure; existing failures were classified by the backfill.

Negative / accepted:
- **Reprocessing charges prepaid clients again** for the new attempt (as the single retry already did). A refused charge is shown, not silently skipped.
- **A dead-lettered message may have been delivered** (a crash after the provider accepted it but before the acknowledgement); reprocessing then sends a duplicate. This is the general at-least-once trade-off; the reprocess count and the provider message id help judge it.
- **Bulk runs are bounded** (1000 per call) and sequential, so very large backlogs are cleared by repeating the call; the response says how many still match.
- The classification of legacy failures is a guess from the error text.
- Clients cannot reprocess their own failures yet; that is an operator action.

## Follow-ups

Client-side "retry my failed messages" with the client's own credit; optional automatic redrive of `EXHAUSTED` messages once a provider's circuit closes; alert on the dead-letter count and age; keep a history of who reprocessed what (needs the admin audit log).
