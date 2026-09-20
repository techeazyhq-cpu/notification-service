# ADR-008: Shorter write path for accepting requests

- **Status:** Accepted
- **Date:** 2026-09-20

## Context

`POST /v1/notifications` answers 202 once a request is durable. The architecture review named the write path as the first place to cut latency. Nothing had been measured, so the first step was to time each stage (`notification.ingest.stage` in Prometheus: `auth`, `rate_limit`, `content`, `sender`, `persist`, `publish`), then remove the biggest costs.

Before the change, one request made these database round trips, each taking its own connection from a pool of 10: template lookup (one or two queries), the billing account and plan lookups inside the storing transaction, the insert transaction, and then a second transaction to mark the messages QUEUED after the broker confirmed them. The template lookup alone had a median of 56 ms at 50 concurrent clients, almost all of it waiting for a connection.

## Decision

1. **Cache the template lookup** for 5 seconds (`client-api.template-cache-seconds`, 0 disables). Only hits are cached, so a template created a moment ago is found at once. Create, edit and delete through the API drop the client's entries after the transaction commits, so they take effect immediately on that instance. Changes made elsewhere (an administrator editing a shared template, another instance) are seen after at most 5 seconds.
2. **Mark QUEUED off the request path.** After the broker confirms a message the request is answered; the PENDING to QUEUED update is batched by `QueuedMarker` (every 50 ms, up to 1000 ids per statement, flushed on shutdown). The update only touches rows still PENDING, so it cannot undo dispatcher progress. If a process dies before a flush, the messages stay PENDING although they were published; the outbox sweeper publishes them again after its age threshold, which delivery already tolerates because each message is claimed atomically (at-least-once, never concurrently).
3. **Cache the billing account and plan reads** used by admission for 5 seconds (`billing.admission-cache-seconds`, 0 disables), including "this client has no account". Money is never cached: balances and holds are read and changed by atomic statements. The admission use case now depends on read-only ports (`AccountLookup`, `PlanLookup`) so the cache is a decorator in the infrastructure layer and the domain and use cases are unchanged.
4. **Connection pool of 20** for the client API (`DB_POOL_SIZE`, was the default 10).
5. **Keep the timers.** Per-stage timers with percentiles stay in, so the next round starts from data.

## Evidence

Single-message SMS with a template, 20 seconds per run, rate limit lifted, dispatcher stopped so the load is ingest only, PostgreSQL, Redis and Pulsar in Docker on a 12-core Windows laptop with the load generator on the same machine. One run per cell.

| Concurrent clients | Before: req/s, p50 / p95 ms | After: req/s, p50 / p95 ms |
|---|---|---|
| 3 | 102, 29 / 47 | 147, 18 / 33 |
| 50 | 265, 178 / 310 | 468, 99 / 184 |
| 200 | 258, 719 / 1465 | 539, 337 / 755 |

Throughput roughly doubles and median latency roughly halves at 50 and 200 clients. Steps at 50 clients: the two caches and the deferred QUEUED update took 240 to 406 req/s (p50 198 to 103 ms); moving the pool from 10 to 30 connections added another 20 percent (498 req/s).

Checked live afterwards: an edited template applied to the very next request (the two requests kept "version ONE" and "version TWO" as their snapshots), an unknown template is still 400, and requests move on to SENT through the dispatcher.

## Consequences

Positive: about twice the ingest capacity per instance and lower latency, with no change to the API, the idempotency rules or the durability promise (202 still means stored and published).

Negative / accepted:
- **Staleness windows of up to 5 seconds:** template edits made on another instance or by an administrator; a billing account that is suspended, changed, or newly created (a new prepaid account is not charged for up to 5 seconds); plan price changes.
- **A crash between publish and flush** leaves published messages PENDING until the sweeper republishes them, so a duplicate broker message is possible. Delivery is protected by the atomic claim.
- The remaining time is dominated by the Redis rate-limit call and the insert transaction. A single run per cell on a laptop is a guide, not a benchmark; repeat on the target hardware.

## Not done, and why

- **Read cache or a read model for status queries (CQRS projector):** a separate concern; the read side was not the bottleneck measured here.
- **Virtual threads:** measured separately in ADR-006 and slower on Java 21.
- **Local pre-check for the rate limiter:** would trade limit accuracy across instances for latency; not needed yet.

## Follow-ups

Run the same test against the deployment target; consider `rewriteBatchedInserts` and a leaner request insert for bulk; a status-read cache if the read path becomes hot; export the stage timers to a dashboard and alert on `persist` and `publish` p95.
