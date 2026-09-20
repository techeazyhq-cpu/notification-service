# ADR-006: Virtual threads are available but stay off

- **Status:** Accepted
- **Date:** 2026-09-20

## Context

The services run Java 21 and Spring Boot 3.3, so request handling can use virtual threads (`spring.threads.virtual.enabled`). Every request to the Client and Admin APIs blocks on PostgreSQL, Redis or Pulsar, which is the workload virtual threads are meant for. Nothing had been enabled or measured.

## Decision

Add the switch `spring.threads.virtual.enabled` to `client-api` and `admin-api`, controlled by `VIRTUAL_THREADS_ENABLED`, and **leave it off by default**. Do not enable it in the dispatcher.

## Evidence

Load test of `POST /v1/notifications` (single SMS, template, 202) against the client API in Docker Compose on a 12-core Windows laptop, Java 21.0.12, with the per-client rate limit lifted so requests are accepted instead of answered 429. 15 seconds per run, one run per cell, PostgreSQL, Redis, Pulsar and the dispatcher on the same machine.

| Concurrent clients | Platform threads: accepted req/s, p50 / p95 ms | Virtual threads: accepted req/s, p50 / p95 ms |
|---|---|---|
| 50 | 132, 347 / 647 | 122, 379 / 697 |
| 200 | 274, 685 / 1311 | 161, 1080 / 2865 |
| 500 | 311, 1528 / 3297 | stalled: 500 of 500 requests failed and the service stopped answering health checks until restarted |

With virtual threads the process used about 67 operating-system threads under load instead of about 250, so the switch did take effect. Thread dumps taken with `-Djdk.tracePinnedThreads` showed virtual threads **pinned** while blocked on monitors inside Hibernate (`ConcreteSqmSelectQueryPlan.withCacheableSqmInterpretation`, `ConcurrentHashMap.compute`). On Java 21 a virtual thread that blocks inside `synchronized` keeps its carrier thread, and the database connection pool (20 connections in this test) then starved.

Caveats: single runs on a shared laptop, so treat differences of 10 to 20 percent as noise; the 200 and 500 client results are large enough to show a direction, not an exact figure. The stall was seen once and not root-caused beyond pinning.

## Consequences

- The default behaviour is unchanged. Platform threads remain the tested configuration.
- **Dispatcher:** the switch would not help there. Delivery runs on Pulsar's own listener threads (`consumers-per-channel`), which Spring's setting does not touch. Using virtual threads for provider calls needs a design change (hand messages to a virtual-thread executor with explicit acknowledgement and back-pressure).
- **Revisit** on Java 24 or later, where blocking inside `synchronized` no longer pins (JEP 491), or after moving off the pinning code paths, and re-run the same test. Also size the connection pool deliberately first, because virtual threads remove the request-thread limit and the pool becomes the only limit.
- To try it: `VIRTUAL_THREADS_ENABLED=true docker compose --profile app up -d client-api`.
