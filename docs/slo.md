# Service level objectives and alert runbook

The objectives the notification service is operated against, how each is measured, and what to do when an alert
fires. The Prometheus rules that implement them are in
[`notification-slo.rules.yml`](../deploy/observability/prometheus/notification-slo.rules.yml),
unit-tested in CI with `promtool`. The decision record is [ADR-024](adr-024-slos-and-alerts.md).

All objectives are over a rolling 30 days. Each has an error budget: the share of events allowed to be bad. Alerts
fire on the **burn rate**, how fast the budget is being spent, measured over a long and a short window together, so
that a brief spike does not page anyone and a fixed problem stops alerting quickly:

| Alert | Burn rate | Windows (both must exceed) | Budget spent if it continues | Severity |
|---|---|---|---|---|
| `...BudgetBurnFast` | 14.4× | 1 h and 5 min | 2% in one hour | critical (page) |
| `...BudgetBurnSlow` | 6× | 6 h and 30 min | 5% in six hours | warning (ticket) |

## Objectives

| Objective | Target | Good event | Measured from |
|---|---|---|---|
| <a id="accept-availability"></a>**Accept availability** | 99.9% | An accept call (`POST /v1/notifications`, `/bulk`, `/bulk/upload`) that does not answer 5xx | `http_server_requests_seconds_count` (client-api) |
| <a id="accept-latency"></a>**Accept latency** | 99% | A single send (`POST /v1/notifications`) answered within 250 ms | `http_server_requests_seconds_bucket{le="0.25"}` |
| <a id="delivery-freshness"></a>**Delivery freshness** | 99% | A sent message whose provider confirmed it within 5 minutes of acceptance | `notification_delivery_latency_seconds_bucket{le="300.0"}` (dispatcher) |
| <a id="delivery-success"></a>**Delivery success** | 99.5% | A message that did not fail for a reason on our side | `notification_dispatch_total`, `notification_dead_letter_total` |
| <a id="otp-delivery"></a>**OTP delivery** | 99% | A one-time password whose provider confirmed it within 30 seconds of acceptance | `notification_delivery_latency_seconds_bucket{category="OTP",le="30.0"}`, `notification_dispatch_total{outcome="failed_expired"}` |

Notes on what counts:

- **Accept availability:** 4xx answers (invalid request, bad API key, rate limited, no credit) are the caller's
  outcome, not a failure of the service. Only 5xx spends budget.
- **Accept latency** covers single sends only. A bulk accept of thousands of recipients is expected to take longer
  (about 1 s for 10,000; see the quality-attribute analysis) and is covered by availability alone.
- **Delivery success:** a provider rejecting a recipient as invalid (`failed_permanent`) is the caller's data, not our
  failure, and spends no budget. Retries exhausted (`failed_exhausted`) and broker dead letters do.
- **Freshness counts only messages that were sent.** A backlog that sends nothing produces no events at all, which
  is why [`NotificationBacklogStale`](#backlog-stale) exists alongside it.
- **OTP delivery counts expired OTPs as bad events,** as well as OTPs sent after 30 seconds. An OTP that expires is
  never sent, so it never reaches the latency histogram. Counting only sent OTPs would make the objective look
  healthiest exactly when OTPs are being dropped. OTPs are also inside delivery freshness, whose 5-minute threshold is
  far too loose for them (ADR-033). Expired OTPs do not spend the delivery-success budget; they are this objective's.

## Runbook

### Accept availability burning

client-api is answering 5xx. Its logs are JSON with the trace id. Find the failing requests by `http.response.status`
in your log platform, or open a trace from an `X-Trace-Id` a client sent you (ADR-023). Usual causes:

- PostgreSQL unavailable or out of connections: accepts wait for the pool, then fail.
- A deployment gone wrong.

The broker being down does **not** fail accepts: they commit to the database and the outbox sweeper publishes them
later (ADR-001).

### Accept latency burning

Accepts are slow, not failing. Check the `notification_ingest_stage_seconds` timers to see which stage is slow:
`auth`, `rate_limit`, `content`, `sender`, `persist` or `publish`. Usual causes:

- **`publish`:** the broker is slow or down. Each accept waits up to the publish timeout before leaving the message
  to the sweeper.
- **`persist`:** the database is under load.
- **Saturation:** client-api is past its measured capacity of about 750 accepts per second per instance. Add
  instances.

### Delivery freshness burning

Messages are being sent, but late. Check `notification_backlog_messages` per status, then:

- **Large `QUEUED`:** the dispatcher is behind. Raise `CONSUMERS_PER_CHANNEL` or add dispatcher instances.
- **Large `RETRYING`:** providers are failing transiently. Check
  `notification_provider_send_seconds_count{error!="none"}` and the provider's own status.
- **Rate limiting:** platform-wide channel limits may be holding messages back on purpose (`outcome="rate_limited"` in
  `notification_dispatch_total`).

### Delivery success burning

Messages are failing after all retries, or being dead-lettered. The admin UI's **Dead letters** page groups them by
error. Fix the cause (usually a provider), then reprocess them there; reprocessing is safe and audited (ADR-010,
ADR-019).

### OTP delivery burning

One-time passwords are reaching providers late, or expiring before they are sent. Users are waiting for codes. Check:

- **`notification_dispatch_total{outcome="failed_expired"}` rising:** OTPs are expiring unsent. Check whether the
  channel's providers are down (`NotificationProviderCircuitOpen`) or the broker is (`NotificationBrokerCircuitOpen`).
  An outage longer than an OTP's validity expires every OTP caught in it, by design (ADR-033).
- **Sent, but late:** check `notification_backlog_messages` for the channel. The priority lane has consumers of its
  own (`PRIORITY_CONSUMERS_PER_CHANNEL`); raise it if OTPs queue. Check `outcome="rate_limited"` too: OTPs may use the
  reserve, but a bucket that is empty is empty.
- **Provider latency:** `notification_provider_send_seconds` for the channel. A slow provider delays every OTP.

### <a id="backlog-stale"></a>`NotificationBacklogStale`

A message accepted more than 15 minutes ago is still `PENDING` or `QUEUED`; nothing is moving it. Check in this order:

1. **Is the dispatcher up?** See `NotificationServiceDown`.
2. **Are all providers of a channel down?** `NotificationProviderCircuitOpen` and `GET :8082/actuator/providerhealth`.
   Consumers of such a channel pause on purpose and resume by themselves.
3. **Is the broker up?** Messages stuck in `PENDING` were never published. The sweeper republishes them once the
   broker is back.

### <a id="service-down"></a>`NotificationServiceDown`

Prometheus cannot scrape one of the services on its management port (ADR-021). Check that the container or pod is
running and healthy. Rule out a network policy blocking the scrape.

### <a id="provider-circuit-open"></a>`NotificationProviderCircuitOpen`

A provider has been failing for 5 minutes and is no longer called. Failover sends through the channel's next provider
if one exists. If none does, that channel's messages wait without spending retries, and are sent when the provider
recovers. Check the provider's status page and its credentials in the admin UI. Saving the provider resets its
circuit.

### <a id="broker-circuit-open"></a>`NotificationBrokerCircuitOpen`

A service has stopped publishing to Pulsar because publishes kept failing (ADR-032). Accepts still succeed and answer
at once: the messages are stored as `PENDING`, and the outbox sweeper publishes them when the broker answers again,
so nothing is lost, but nothing is delivered meanwhile and `NotificationBacklogStale` follows. Check the Pulsar
brokers and the service's connectivity and TLS trust to them. The circuit probes the broker every 10 seconds and
closes on its own once publishes succeed.

### <a id="dead-letters"></a>`NotificationDeadLetters`

The broker gave up on messages after repeated redelivery. They are recorded as `FAILED` (`DEAD_LETTERED`) and listed
under **Dead letters** in the admin UI. Find the cause in the dispatcher's logs around the time, then reprocess.

### <a id="rate-limiter-failing-open"></a>`NotificationRateLimiterFailingOpen`

Redis is not answering. The services keep working, but client and channel rate limits are **not enforced**: a client
can exceed its quota, and providers can receive more than their configured rate. Restore Redis. The limiter tries
Redis again every 5 seconds and recovers on its own (ADR-024).

### <a id="audit-write-failures"></a>`NotificationAuditWriteFailures`

Administrator actions are succeeding without an audit record (ADR-019). The admin-api logs name each action whose
record was lost. Usually PostgreSQL is struggling, which other alerts will show too. Treat any administrator change
made during the window as unaudited and review it.

### <a id="delivery-rejections-rising"></a>`NotificationDeliveryRejectionsRising`

Over 5% of a channel's messages have been rejected by its providers (`DELIVERY_REJECTED`, NS-6001) for 15 minutes,
with at least 20 rejections. Rejections are permanent failures and spend no delivery-success budget, because they are
usually the caller's data. A sudden rise on one channel is more often a provider-side change: a new filtering rule,
a sender id or template that lost its registration, an account suspended, a changed API. Check:

- the **Dead letters** page filtered to the channel and `PERMANENT`: the provider's own words are in the error text;
- whether it is one client (bad data, a client issue) or every client (a provider issue);
- the provider's status page and account.

Failures by every error code are counted in `notification_delivery_errors_total{channel, code, error_id}`, with codes
from the [error code dictionary](error-codes.md).
