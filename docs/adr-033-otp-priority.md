# ADR-033: One-time passwords are delivered with priority, and never after they expire

- **Status:** Accepted
- **Date:** 2026-10-02

## Context

A one-time password (OTP) is only useful for a few minutes, while a user waits for it. Every message took the same
path:

- one broker topic per channel;
- one pool of consumers per channel;
- one rate-limit bucket per client and channel, and one per channel.

So a 50,000-recipient promotion accepted a second earlier queued every OTP behind it. Worse, it could use up the
channel's rate-limit tokens, and a code that arrived ten minutes late was still delivered, confusing a user who had
already asked for another.

The owner chose:

- a message category on requests, rather than a free priority flag, so the priority follows from what the message
  is and cannot be claimed by ordinary traffic;
- dropping OTPs that cannot be delivered in time, rather than sending them late.

## Decision

1. **A category on every request, message and template:** `OTP`, `TRANSACTIONAL` (the default) or `PROMOTIONAL`.
   - Single sends accept `category` and, for OTP only, `validitySeconds` (60–900, default 300).
   - Bulk sends and CSV uploads accept `TRANSACTIONAL` or `PROMOTIONAL`. An OTP bulk is refused with
     `CATEGORY_NOT_ALLOWED` (NS-1005), because one-time passwords are per user.
   - Templates may set a default category, so a "login code" template makes every send from it an OTP. An explicit
     category on the request wins.
2. **Its own lane, end to end.** OTPs are published to the channel's priority topic, e.g. `notification-sms-priority`,
   with its own retry and dead-letter topics.
   - The dispatcher reads that topic with consumers of its own (`dispatcher.priority-consumers-per-channel`,
     default 2), so an OTP never waits behind the standard topic's backlog.
   - Both lanes pause together when every provider of the channel is down.
   - The dead-letter recorder reads both lanes.
3. **A reserve in every rate-limit bucket.** Ordinary messages may not take the last `priority-reserve-fraction`
   (default 20 %) of a client's or a channel's bucket; OTPs may. Provider throughput limits still hold: OTPs share
   the bucket, they don't get a second one. A bulk send can no longer use up the tokens an OTP needs. The reserve is
   enforced atomically in the Redis script, which a test runs against a real Redis.
4. **Expiry.** An OTP's `expiresAt` is set when it is accepted.
   - The dispatcher checks it before anything else, even during a provider outage, and ends an expired OTP as
     `FAILED` / `EXPIRED` with `OTP_EXPIRED` (NS-6007). It claims the message first, so a worker sending it at that
     moment is never overruled.
   - An expired OTP cannot be requeued from the dead-letter screen.
   - `FailureKind.retryable()` now lists the retryable kinds explicitly, so a new kind is not retryable by accident.
5. **Swept first.** The outbox sweeper republishes stuck OTPs before older ordinary messages; a partial index keeps
   that query cheap.
6. **Measured on their own.** Delivery latency carries a `category` tag, so OTP time-to-send can be watched and alerted
   on separately. Existing SLO rules sum over it and are unchanged.

## Options considered

- **An explicit `priority` field:** simpler, but any caller could mark bulk traffic as high priority, and the service
  would not know a message is an OTP, so it could not expire it or keep it out of bulk sends.
- **Pulsar message priority on one topic:** Pulsar has no per-message priority within a subscription; a separate
  topic per lane is the standard way to isolate it.
- **Separate rate-limit buckets for OTPs:** isolation without the reserve, but it doubles what a channel may send to a
  provider and breaks its contractual throughput.
- **Sending late OTPs anyway:** some clients might prefer it, but a stale code fails at the client's verification step
  and makes the user ask again. The validity is configurable per request up to 15 minutes instead.

## Consequences

Positive: OTPs reach providers ahead of any backlog and are never starved of rate-limit tokens. A code nobody can
use any more is not sent, and its failure carries a precise code. OTP delivery time is observable on its own.

Negative / accepted:

- **Each channel has twice the topics,** consumers and subscriptions. Topics are auto-created; deployments that
  pre-create topics must add the `-priority` ones.
- **Ordinary traffic gives up 20 % of each bucket's burst** while OTPs are not using it, which lowers peak bulk
  throughput a little. The fraction is configurable, and 0 turns the reserve off.
- **The API grew** by three request fields and four view fields. All are optional and additive.

## Follow-ups

- An SLO for OTP time-to-send (for example 99 % within 30 seconds) with burn-rate alerts, using the new tag.
- Per-category pricing in billing, if the business wants OTPs priced differently.
