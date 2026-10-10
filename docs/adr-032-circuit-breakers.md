# ADR-032: A circuit breaker on every dependency whose failure would otherwise spread

- **Status:** Accepted
- **Date:** 2026-10-02

## Context

The owner asked to make sure circuit breakers are in place. An audit of every outbound call found that the delivery
path was already well protected (ADR-001, ADR-010, ADR-024). One gap remained, finding N3 of the architecture
review: while Pulsar is down, every accept waits out the 5-second publish timeout before answering. That turns a
broker outage into slow responses for every client, and a pile-up of request threads.

## Decision

1. **The broker publisher gets a circuit breaker.**
   - `CircuitBreakingMessagePublisher` wraps the Pulsar publisher as the primary `MessagePublisher`. It is a
     Resilience4j breaker named `broker`: it opens at 50 % failed publishes over the last 20, once at least 10 were
     made.
   - While open, publishes fail at once with `BrokerUnavailableException`. The accept answers immediately; the
     messages are already committed as `PENDING`, and the outbox sweeper publishes them once the broker is back.
   - After 10 seconds, three probe publishes decide whether to close.
   - Slow publishes count too: one the broker confirms only after 2 seconds is slow, and 50 % slow publishes open
     the circuit like failures do. A broker that hangs rather than refuses is caught before every publish waits out
     its 5-second timeout (`slow-call-duration-threshold-ms`, `slow-call-rate-threshold`).
   - The thresholds are under `notification.pulsar.circuit-breaker.*`, and `enabled: false` turns the breaker off.
   - The outbox logs one summary line per batch instead of a warning per message while the circuit is open.
2. **The breaker is visible and alerted.**
   - Its state is exported as `resilience4j_circuitbreaker_state{name="broker"}`, the same metric the provider
     breakers use.
   - `NotificationBrokerCircuitOpen` (critical, 2 minutes) has its own runbook entry.
   - `NotificationProviderCircuitOpen` now excludes the broker. Without that it would have reported "Provider broker
     has been failing"; a rule test caught it.
3. **The rest of the inventory is confirmed and recorded here,** so a future change keeps it.

| Dependency | Failure mode guarded against | Protection | Where |
|---|---|---|---|
| SMS, e-mail and push providers | Outage, timeouts, throttling | One circuit breaker per provider (opens at 50 % transient failures over 20 calls; permanent rejections do not count), failover to the channel's next provider by priority, and consumers paused while every provider of a channel is open, so the backlog waits in Pulsar without spending retries | `ResilienceConfig`, `ProviderRegistry`, `DispatchConsumers` |
| Provider calls | A hung connection | HTTP connect 5 s and read 10 s (configurable per provider); SMTP connect 5 s, read and write 10 s | `HttpJsonProvider`, `FcmProvider`, `SmtpSenderFactory` |
| Broker (publish) | Outage | **Circuit breaker `broker` (this ADR)**, publish timeout, outbox sweeper | `CircuitBreakingMessagePublisher`, `OutboxSweeper` |
| Broker (consume) | Poison messages | Retry topic with back-off, dead-letter topic, dead-letter recorder | `DispatchConsumers`, ADR-010 |
| Redis (rate limits) | Outage | Fails open: limits are skipped and Redis is not called again for 5 s after an error, counted and alerted. A breaker in all but name | `RedisRateLimiter`, ADR-024 |
| PostgreSQL | Restart or failover | Deliberately no breaker: an accept is a database commit, so accepts wait for the connection pool rather than fail; drilled with no request lost (quality-attributes analysis) | Connection pool |
| Trace exporter | Collector down | Asynchronous batch export that drops spans; never on the request path | OpenTelemetry SDK |

## Options considered

- **A breaker around PostgreSQL too.** Failing accepts fast while the database is down would lose them; waiting for
  the pool keeps them, and failover takes under a minute. Rejected.
- **Converting the Redis fail-open to a Resilience4j breaker,** for one consistent metric. Today's behavior is
  already tested and alerted (ADR-024), so the change would be cosmetic. Left as is.
- **Shorter publish timeouts instead of a breaker.** Each accept would still wait during an outage, just less; and
  set too short, the timeout fails healthy publishes under load.

## Consequences

Positive: during a broker outage, accepts stay as fast as usual and nothing is lost. The outage is alerted on its
own, rather than only through a stale backlog later. Every dependency's protection is written down in one place.

Negative / accepted:

- **Up to 10 seconds of delay after the broker recovers,** until the breaker's probes close it. The sweeper then
  catches up, and the delay is bounded by its interval.
- **Each service instance has its own breaker,** so instances open and close independently.
