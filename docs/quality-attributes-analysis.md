# Quality-attribute analysis

Measured on 2026-09-26 against the code in this repository. Every number below comes from a file in
[`docs/benchmarks`](benchmarks) produced by [`perf/run-benchmark.mjs`](../perf/run-benchmark.mjs) or
[`perf/chaos-drills.mjs`](../perf/chaos-drills.mjs); rerun them to reproduce. This document replaces the
findings that no longer hold in [architecture-review.md](architecture-review.md) (written earlier the same week).

**Test bed.** One instance of each service, single-node PostgreSQL 16, Redis 7 and Pulsar 3.3 (standalone), all in
Docker Desktop (8 GB VM) on a 12-core laptop, with the load generator (k6) on the same machine. This is a
single-machine result: it shows shape and relative cost, not production capacity. Providers are the local catcher
(instant replies), so provider latency is not part of any figure.

## Verdict

| Attribute | Rating | Evidence in one line |
|---|---|---|
| Durability | Strong | 659/659 requests accepted during a database restart were delivered; 100/100 accepted with the broker down; no accepted request lost in any drill |
| Reliability | Strong | Provider outage: 0 messages failed, breaker held 300 in the queue, all delivered 17 s after recovery |
| Fault tolerance | Good, two known weaknesses | Crash of the delivery worker recovers fully but takes up to ~7 min; ingest slows to ~5 s per request while the broker is down |
| Scalability | Adequate for a first deployment | Ingest sustains 750 req/s (p99 273 ms) on one instance; delivery is capped near 300 msg/s per dispatcher at 2 consumers per channel |
| Traceability | Adequate | Every message has a persisted state machine and error text; no distributed tracing or correlation id |
| Security in transit / at rest | Good | Edge TLS, TLS on every datastore hop, encrypted variables and credentials; recipient column relies on storage encryption |
| Static quality | Clean | SonarQube: 0 bugs, 0 vulnerabilities, 0 code smells, 0 security hotspots across Java and the front ends |

## Performance benchmark

### Ingest (`POST /v1/notifications`, single SMS from a template) and status reads

| Offered rate | Accepted in 60 s | p50 | p95 | p99 | max | Errors | Status read p50 / p99 |
|---|---|---|---|---|---|---|---|
| 200 req/s | 12,001 | 15 ms | 38 ms | 88 ms | 238 ms | 0 | 7 / 29 ms |
| 500 req/s | 30,001 | 16 ms | 31 ms | 47 ms | 119 ms | 0 | 7 / 25 ms |
| 750 req/s | 44,799 | 30 ms | 185 ms | 273 ms | 1,284 ms | 1 in 44,800 | 8 / 38 ms |
| 1,000 req/s | 40,582 (≈ 676/s) | 773 ms | 1,392 ms | 2,166 ms | 4,589 ms | 0 | 15 / 90 ms |

- **Knee between 750 and 1,000 req/s** on one client-api instance: at 1,000 the service cannot keep up (achieved
  ≈ 676/s, latency climbs into seconds) but does not fail requests. The 100 ms p99 target from the design document
  is met to 500 req/s and missed above it.
- The 200 req/s row ran while the previous run's backlog (31,882 messages) was still draining, so it is
  pessimistic; the same load on a quiet system, over encrypted transport, gave p50 13 / p95 25 / p99 49 ms.

### Bulk and delivery

| Measure | Result |
|---|---|
| 10,000-recipient bulk accepted | 0.8 – 1.5 s (7,000 – 12,500 recipients/s) |
| Delivery of that bulk (2 consumers per channel) | 28 – 33 s, 300 – 360 messages/s |
| Backlog drain after an overload run | 13,390 in 46 s, 30,766 in 111 s, 30,128 in 124 s (243 – 290 messages/s) |
| Failures in 10,000 | 1, the recipient the local catcher rejects on purpose |

**Ingest is roughly 2.5× faster than delivery.** That is by design (the durable queue absorbs the difference; a 30,000
message burst drained in about two minutes with zero loss) but it is also the first scaling limit: raise
`dispatcher.consumers-per-channel`, run more dispatcher instances, and partition the Pulsar topics (currently single
partition) to go beyond ~300 messages/s.

### Cost of encryption

Same 200 req/s test with TLS on PostgreSQL, Redis and Pulsar and encrypted message variables:
p50 13 ms, p99 49 ms, 0 errors, bulk delivery 359 messages/s. No measurable cost at this load; the AES-GCM step is
microseconds per message next to a database round trip.

### Footprint after ~356,000 messages

client-api 870 MB, dispatcher 648 MB, admin-api 500 MB, PostgreSQL 457 MB, Pulsar 992 MB, Redis 15 MB, both UIs
~10 MB each. The database is the shared hot path (finding C1 in the architecture review still stands).

## Durability

- **Accept is a database commit.** A `202` means the request and all its messages exist as `PENDING` rows; the
  broker publish comes after. Drill: with the broker stopped, 100 of 100 requests were accepted and, once the broker
  returned, all 100 were delivered within 50 s.
- **Database restart under load:** 659 requests attempted while PostgreSQL was restarted, 659 accepted, 0 rejected,
  0 lost, all delivered. Requests stalled on the connection pool rather than failing (throughput dipped to about half),
  which is the right trade for an accept path.
- **Personal data lifecycle:** finished messages lose recipient and variables after 90 days and are deleted after 400
  (ADR-009); erasure on request.
- **Gap:** no backups, point-in-time recovery or restore drill in this repository. Durability of the datastores
  themselves is a deployment concern (see the checklist).

## Reliability and fault tolerance (drills)

| Drill | What was broken | Outcome |
|---|---|---|
| Dispatcher killed mid-delivery | `docker kill` during a 1,200-message bulk | All 1,200 delivered; **1 duplicate at the provider** (1,201 received, 1,200 unique). Recovery took 425 s because in-flight rows wait for the 5-minute stuck-processing sweep |
| Provider outage | Provider returns 503 for every call | **0 failed**; breaker opened; 287 held queued, 13 retrying; all 300 delivered 17 s after the provider recovered |
| Broker down during ingest | Pulsar stopped, 100 requests sent | 100/100 accepted (rows `PENDING`), all delivered 50 s after the broker restarted |
| Database restart under load | PostgreSQL restarted 10 s into 60 s of ingest | 659 accepted, 0 rejected, 0 lost |

**Defects the drills found, fixed in this change:**

1. *Messages lost between broker and worker.* After the dispatcher crash, 2 of 1,200 messages stayed `QUEUED` for
   over 15 minutes and never delivered: the broker had released them and the sweeper only reclaimed `PENDING` and
   `PROCESSING`. The sweeper now also republishes rows `QUEUED` longer than `notification.sweeper.queued-timeout-seconds`
   (default 900); republishing is safe because workers claim atomically. After the fix the same drill delivered
   1,200 of 1,200. (This was the second half of finding R1 in the architecture review.)
2. *Ingest slowed to 32 s per request during a broker outage.* Producer creation blocked for the 30 s operation
   timeout on every request. Producers are now created without blocking and a publish gives up after
   `notification.pulsar.publish-timeout-seconds` (default 5), leaving the row `PENDING` for the sweeper. Ingest during
   an outage is now about 5 s per request, still accepted, still safe.

**Still open:** duplicates after a worker crash are inherent to at-least-once delivery (providers do not get an
idempotency key from us for SMTP); stuck-processing recovery takes up to 5 minutes; while the broker is down each
accept still waits the publish timeout (a circuit breaker on the publisher would make that instant); every datastore
is single-node.

## Traceability

- **Present:** each message has a persisted status, attempt count, last error and failure kind; a request's status is
  derived from its messages; dead letters are recorded with a reason; Prometheus counters per channel and outcome
  (`notification.dispatch`, `notification.dead_letter`, ingest stages); billing is a ledger; admin sign-ins are
  recorded by sessions.
- **Missing:** no correlation id from the HTTP request through Pulsar to the provider call, no OpenTelemetry traces, no
  audit log of administrator actions (who changed a provider, rotated a key, reprocessed dead letters), logs are not
  structured. When a message misbehaves you can read its row, but you cannot follow one request across services.

## Scalability

| Component | Limit observed or known | How to raise it |
|---|---|---|
| client-api | knee at ~750 req/s per instance | stateless: add instances behind the proxy; the rate limiter is already shared (Redis) |
| dispatcher | ~300 messages/s with 2 consumers per channel | more consumers per channel, more instances, partitioned topics |
| PostgreSQL | single primary; `notification_message` unpartitioned, status counted on read | time-partition messages, read model for status and dashboards, read replica |
| Pulsar | standalone, single-partition topics | cluster, partition per channel |
| Redis | single node; rate limiter fails open | replicas; alert on limiter errors |

## Enhancements, in priority order

1. **Traceability:** correlation id and OpenTelemetry across client-api → Pulsar → dispatcher; append-only admin audit log.
2. **Resilience:** circuit breaker on the publisher so ingest stays fast during a broker outage; shorter, configurable
   stuck-processing timeout with a heartbeat instead of a fixed 5 minutes; provider idempotency keys.
3. **Scale:** partitioned topics and per-channel consumer tuning; time-partitioned `notification_message` with retention by
   partition drop; a status read model.
4. **Data protection:** encrypt or blind-index the recipient column (ADR-017); mTLS to datastores; key rotation with a
   key id in the stored envelope.
5. **Operations:** backups with a tested restore, HA topologies (PostgreSQL replica, Redis Sentinel, Pulsar cluster),
   SLO dashboards and alerts on accept latency, backlog age and failure ratio; make CI and Security required checks.
6. **Product:** delivery receipts and webhooks to clients, scheduled sends, template localisation, an admin-ui page for
   administrator roles.

## Production checklist (things this repository cannot enforce)

- [ ] Encrypt the PostgreSQL volume and its backups at the storage layer (LUKS or a KMS-backed cloud volume).
- [ ] Set `SECRETS_ENCRYPTION_KEY`, `DATA_ENCRYPTION_KEY`, `ADMIN_TWO_FACTOR_KEY`, `ADMIN_PASSWORD` and both database
      passwords from a secrets manager; the services refuse the shipped defaults unless the `local` profile is set.
- [ ] Use certificates from your own PKI (or Let's Encrypt for the edge) in place of the generated private CA.
- [ ] Run PostgreSQL, Redis and Pulsar as replicated or managed services; take and restore-test backups.
- [ ] Make the CI and Security workflows required status checks on `main`.
- [ ] Alert on backlog age, accept latency, failure ratio and rate-limiter errors.

## Static analysis

SonarQube Community (sonarqube:community image), default "Sonar way": Java (`notification-service`) 0 issues, 0 bugs, 0 vulnerabilities,
0 code smells, 0 hotspots to review, 0.3 % duplication, 63 % line coverage over 8,399 lines; JavaScript, TypeScript,
CSS, Docker and YAML (`notification-service-frontend`) 0 issues, 0 hotspots. Fixing the findings also corrected a real
bug: the "Copy as curl" command in the client UI joined lines with a literal `\n` instead of a shell line
continuation, so a copied multi-line command did not run.
