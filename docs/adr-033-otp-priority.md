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
  is. The client declares the category, so this states intent rather than enforcing it: see "Category is declared,
  not verified" under Consequences;
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
   - The reserve is at least one token whenever the fraction is above zero and the burst is two or more. Otherwise a
     bucket with a burst under 5 would round its 20 % down to nothing and keep no token for an OTP.
4. **Expiry.** An OTP's `expiresAt` is set when it is accepted.
   - The dispatcher checks it before anything else, even during a provider outage, and ends an expired OTP as
     `FAILED` / `EXPIRED` with `OTP_EXPIRED` (NS-6007). It claims the message first, so a worker sending it at that
     moment is never overruled.
   - An expired OTP cannot be requeued. The dead-letter screen lists `EXPIRED` as a kind of its own, does not let
     it be selected, and leaves it out of every bulk reprocess, including "reprocess everything".
   - `FailureKind.retryable()` now lists the retryable kinds explicitly, so a new kind is not retryable by accident.
5. **Swept first, and in time.** The outbox sweeper recovers stuck OTPs before anything else, with thresholds of
   their own under `notification.sweeper.otp.*`.
   - The thresholds are PENDING 10 s, PROCESSING 60 s, QUEUED 60 s and RETRYING 90 s.
   - The general thresholds (up to 900 s) are longer than an OTP's validity, so a lost OTP would only have been
     recovered after it expired.
   - The OTP sweep has its own query, served by the partial index `ix_message_inflight_otp` (migration 013).
   - The general sweep reads `ix_message_status_updated` in order and stops at the batch size.
   - An earlier version put OTPs first by sorting on category in the general query. That sorted the whole backlog on
     every sweep, and its PENDING-only index did not match. `SweeperQueryPlanTest` pins both plans against a real
     PostgreSQL.
   - Migration 013 builds the index concurrently. The migration run lock now polls `pg_try_advisory_lock`, so that a
     second migration waiting for the lock does not deadlock with the build.
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
- **A new topic can receive messages before anyone subscribes.** In an upgrade, the client API may publish OTPs to
  a `-priority` topic before the new dispatcher has subscribed to it. Without retention, Pulsar trims such messages:
  a forced trim left a late subscriber 0 of 3 messages. The brokers therefore retain messages for 60 minutes (1 GB)
  even without a subscription (`deploy/k8s/pulsar/values.yaml`, and the standalone brokers of `docker-compose.yml`
  and `deploy/k8s/dev`). The dispatcher subscribes from the earliest message, so it then receives all of them, and
  the deployment order does not matter. Messages already handled are skipped by the atomic claim.
- **Category is declared, not verified.** Any client may send single messages as `OTP`. Such messages jump the
  queue, use the reserved tokens and expire. The owner accepted this rather than adding a per-client OTP
  entitlement or an OTP cap: clients are onboarded by the operator, OTPs are single sends only, and per-category
  pricing (a follow-up) would remove the incentive. If misuse appears, an admin-granted entitlement with a
  per-client OTP rate cap is the intended control.
- **Ordinary traffic gives up 20 % of each bucket's burst** while OTPs are not using it, which lowers peak bulk
  throughput a little. The fraction is configurable, and 0 turns the reserve off.
- **The API grew** by three request fields and four view fields. All are optional and additive.

## Follow-ups

- ~~An SLO for OTP time-to-send with burn-rate alerts.~~ Done: **OTP delivery**, 99% within 30 seconds, with expired
  OTPs counted as bad events ([slo.md](slo.md#otp-delivery)).
- Per-category pricing in billing, if the business wants OTPs priced differently.

## Amendment (2026-10-02): lane isolation and template edits

- **Each dispatcher consumer is drained by a worker thread of its own.** The consumers used Pulsar message listeners,
  which the client runs on one shared thread by default. Handling blocks: a provider call, or holding a message
  while it is rate limited or every provider is down. So one held message stopped every lane of every channel, and
  an OTP waited behind a rate-limited bulk message, which defeated decision 2. Workers stop before the consumers
  close, so a message being held is handed back to the broker.
- **An edit that leaves out `category` keeps the template's category.** Both consoles saved templates without the
  field, which reset an OTP template to TRANSACTIONAL with no error. The consoles now show and send the category,
  and the APIs keep the stored one when a caller leaves it out. Sending `TRANSACTIONAL` resets it.
