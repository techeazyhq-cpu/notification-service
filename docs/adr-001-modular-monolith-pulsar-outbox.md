# ADR-001: Modular monolith with three deployables, Pulsar between accept and deliver, DB outbox

- **Status:** Accepted (v0.1)
- **Date:** 2026-09-20

## Context

We need a multi-channel notification platform (Email, SMS, WhatsApp, Push) with a client REST API (single, bulk, status), an admin app (configuration, dashboard, rate limiting) and Apache Pulsar internally. The domain is small (one core capability: dispatch), the team is small, and the scaling profile is asymmetric: ingest is bursty and cheap, delivery is slow and provider-bound.

Drivers, ranked: (1) never lose an accepted request, (2) decouple accept speed from provider speed with controllable delivery rate, (3) keep operations simple.

## Decision

1. **One codebase, one shared library (`notification-core`), three deployables:** Client API, Admin API, Dispatcher. Bounded contexts are enforced by package boundaries, not by network boundaries. All share one PostgreSQL schema.
2. **Pulsar carries only message ids**, one topic per channel, Shared subscription for the dispatcher, Pulsar retry topic for backoff, dead-letter topic for poison messages.
3. **Transactional outbox by status:** requests and messages are committed as `PENDING`; a publish moves them to `QUEUED`; a sweeper republishes stragglers. No dual-write loss window.
4. **Atomic claim** (`UPDATE … WHERE status IN (…)`) before sending, so duplicate broker deliveries are harmless. Delivery is at-least-once with a small duplicate window on worker crash.
5. **Redis token buckets (Lua)** for rate limiting; admin-managed policies in PostgreSQL, cached for 10 s.
6. **Request status is derived** from message statuses, not stored.
7. **Java 21 / Spring Boot 3, React admin SPA**, providers behind a `ChannelProvider` SPI.

## Options considered

- **Microservice per context.** Rejected: the contexts share almost all data, and the split would add network calls, per-service databases and sagas to keep templates/providers/limits consistent, with no scaling benefit beyond what three deployables already give.
- **No broker (DB queue + workers).** Rejected: contradicts the requirement and loses independent backpressure and replay.
- **Publish to Pulsar first, persist in the consumer.** Rejected: a status API needs the request to exist synchronously, and the acknowledgement (`202`) would rest on broker durability alone.
- **Pulsar Functions / Kafka-style stream processing for dispatch.** Rejected for v0.1: more moving parts than a consumer service and harder to unit test.

## Consequences

Positive:
- Ingest stays available when the broker or providers are down; delivery rate is a policy, not an accident.
- Independent scaling of ingest and delivery; one deploy pipeline and one schema to reason about.
- Personal data lives only in PostgreSQL.

Negative / accepted:
- Shared database couples the three deployables at the schema level (mitigated by table ownership and a single migration job, see ADR-003).
- Sweeper-based recovery means a crash can produce a duplicate send after the 5-minute reclaim; providers with idempotency keys should use `messageId`.
- PostgreSQL is on the hot path for bulk; needs partitioning/retention at volume.
- Admin auth is HTTP Basic in v0.1 (temporary).
- Rate limiter fails open.

## Compliance impact

No recipient data in Pulsar topics, retry/DLQ topics or logs, which keeps erasure to a database operation. A retention job and erasure endpoint are required before processing real personal data (GDPR/DPDPA storage limitation and erasure). Provider processors need data-processing agreements; data residency depends on where the chosen SMTP/SMS/push vendors process data.

## Follow-ups

Testcontainers integration suite; OIDC for admin; retention and partitioning; vendor adapters and delivery receipts; async bulk fan-out; trace propagation; revisit the shared schema if a second team takes ownership of a context.
