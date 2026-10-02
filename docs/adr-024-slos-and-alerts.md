# ADR-024: Service level objectives, burn-rate alerts, and a rate limiter that really fails open

- **Status:** Accepted
- **Date:** 2026-10-02

## Context

ADR-023 made a single message traceable. The service still had no stated objectives and no alerts: nobody would be
told that accepts were failing, that messages had stopped moving, or that a provider had been down for an hour. The
architecture review listed this under O1 and R3, and the 2026-10-01 re-review made it the second half of production
blocker 4.

Two signals the alerts need did not exist:

- **The age of the backlog.** "Messages have stopped moving" produces no events at all, so no rate-based alert sees
  it.
- **The time from acceptance to sending.** There was a counter of sent messages, but no measure of how long they took.

Writing the alert for review finding R3 ("the rate limiter fails open when Redis is unavailable, but nothing alerts")
showed that the limiter did not fail open at all. `RedisRateLimiter` handled only a `null` reply. A Redis connection
error propagated, so a Redis outage would have answered every client API call with 500, because client-api checks
the limit on each call, and stalled delivery in a negative-acknowledge loop in the dispatcher. Each of those calls
would first have waited out Spring's default Redis timeout of 60 seconds.

## Decision

1. **Four objectives over 30 days,** defined with their exact queries and exclusions in [`docs/slo.md`](slo.md):

   | Objective | Target |
   |---|---|
   | Accept availability (no 5xx) | 99.9% |
   | Accept latency (single send within 250 ms) | 99% |
   | Delivery freshness (sent within 5 minutes of acceptance) | 99% |
   | Delivery success (not failed for a reason on our side) | 99.5% |

   Caller-caused outcomes (4xx, recipients a provider rejects) spend no budget.
2. **Multi-window, multi-burn-rate alerts** for each objective, as in the Google SRE workbook:
   - **Fast burn** (14.4× over 1 h and 5 min) is critical.
   - **Slow burn** (6× over 6 h and 30 min) is a warning.

   Recording rules compute each indicator's ratio over 5 min, 30 min, 1 h and 6 h.
3. **Symptom alerts for what burn rates cannot see:** a stale backlog (a message `PENDING` or `QUEUED` for over 15
   minutes), a service that cannot be scraped, a provider circuit open for 5 minutes, dead letters arriving, the rate
   limiter failing open, and audit records being lost.
4. **New measurements:**
   - **Delivery latency:** `notification.delivery.latency` is a timer per channel from acceptance to confirmed send,
     with buckets at 10 s, 30 s, 1 min and 5 min.
   - **Backlog gauges:** `notification.backlog.messages` and `notification.backlog.oldest.age` (seconds since the
     oldest was accepted), per in-flight status. The dispatcher refreshes them every 15 s with one grouped query. A
     status with nothing waiting reports zero, so alerts resolve instead of going stale.
   - **Accept latency buckets:** at 100 ms, 250 ms, 500 ms and 1 s on `http.server.requests` in client-api.
5. **The rate limiter now fails open, as documented.**
   - Any Redis error, or an unexpected reply, lets the call through and is counted in
     `notification.rate_limiter.errors`.
   - Redis is then not asked again for 5 seconds, so an outage adds no timeout to every call.
   - Redis command and connect timeouts are 500 ms instead of 60 s.

   The alert exists because limits are not enforced while this happens.
6. **The rules are code.** `promtool check rules` and `promtool test rules` run in CI with unit tests that feed
   synthetic series through the real rules and assert which alerts fire and which stay quiet. That includes the
   exclusions: rejected recipients spend no budget, and retries alone are not a stale backlog.
   `docker-compose.observability.yml` adds Prometheus with these rules, next to Jaeger.

## Options considered

- **Static threshold alerts (error rate above 1% for 5 minutes).** Simple, but they page on short spikes that cost
  little budget and miss slow leaks that cost a lot. Burn rates tie urgency to the objective.
- **Freshness from the backlog age alone.** Catches a standstill but not "everything is a little late". The latency
  histogram measures the objective itself, and the age gauge catches the case with no events.
- **Compute the backlog in Prometheus from counters.** Accepted-minus-sent drifts with restarts and retention. A
  periodic query of the source of truth is exact, and cheap with the existing `(status, updated_at)` index.
- **Leave the rate limiter strict (fail closed).** Protects providers from overload during a Redis outage, but turns a
  cache outage into a full platform outage. Providers are protected by their own circuit breakers. Fail open plus an
  alert is the documented design (ADR-001), and now the actual behaviour.

## Consequences

Positive:

- Operators are told about the four ways the service lets its clients down, with a runbook entry for each alert.
- The alert logic is tested like any other code.
- A Redis outage degrades to "limits not enforced, warning raised" instead of a total outage. This was verified live:
  with Redis stopped, accepts kept answering 202 in about 40 ms after the first call, deliveries continued, and the
  alert fired.

Negative / accepted:

- **The objectives are initial values from local measurements** (quality-attribute analysis). Review them against
  production data after the first month.
- **Every dispatcher instance runs the backlog query** and reports the same gauges. The rules take the maximum. At a
  15 s interval this is negligible next to the dispatch queries.
- **Scrape job names matter.** `NotificationServiceDown` matches `job=~"notification-.*"`, so production scrape
  configs must use the documented job names or adapt the rule.
- **No alert routing in this repository.** Routing (Alertmanager, paging, tickets) belongs to the operating
  environment, so the rules carry `severity` labels and runbook links for it.

## Follow-ups

- Dashboards for the four objectives and their remaining budgets.
- Include bulk accepts in a latency objective of their own once production sizes are known.
