# ADR-039: Detecting abnormal tenant traffic, with statistics first and machine learning only if it earns its place

- **Status:** Accepted
- **Date:** 2026-10-19

## Context

The platform limits what a tenant can spend, by prepaid credit or a postpaid spend cap (ADR-004), but nothing
notices when a tenant's traffic stops looking like that tenant. Three things can go wrong and stay unseen until the
invoice or a provider complaint arrives:

- **SMS pumping.** A fraudster drives one-time password sends to premium-rate numbers and collects a share of the
  termination fees. The traffic is valid by every rule we enforce today; it only looks wrong next to the tenant's
  normal pattern: new countries, new number ranges, many destinations that were never seen before.
- **A leaked API key** used to send spam or phishing under the tenant's name and at the tenant's cost.
- **A defect in the tenant's own system** that sends many times its usual volume or the same message in a loop.

The owner wants anomaly detection and reports on the admin dashboard, and holds a standing principle: no technology
the problem does not need. Machine learning is welcome only where simpler methods are shown to fall short.

Two facts shape the design:

- **The recipient address does not last.** Retention erases it after 90 days (ADR-009). Anything learned over longer
  periods must be learned from aggregates that hold no personal data.
- **The delivery path is latency-sensitive** (ADR-008, ADR-033). Detection must not add work to accepting or sending a
  message.

## Decision

Build detection in two stages. Stage 1 is statistical and ships first. Stage 2, machine learning, is built only if the
evidence from Stage 1 says it would do better.

### Stage 1: per-tenant baselines

1. **Hourly traffic features, `traffic_hourly`.** A scheduled job in `admin-api` aggregates the last completed hour
   from `notification_message` into one row per tenant, channel and hour:

   | Feature | What it catches |
   |---|---|
   | Messages accepted | Volume spikes: a loop, a leaked key |
   | Distinct recipients (by `recipient_fingerprint`, ADR-035) | Fan-out to many new destinations |
   | Distinct country codes, and the share outside the tenant's usual countries | SMS pumping |
   | Share of recipients not seen from this tenant in the previous 30 days | Pumping, spam lists |
   | Failure rate and the share of each error code | Bad lists, number ranges providers reject |
   | Largest bulk request in the hour | Runaway bulk sends |

   - **No personal data:** only counts, ratios and country codes. Rows outlive the 90-day erasure, so baselines can
     cover a year.
   - **Idempotent:** the row is an upsert keyed by `(client_id, channel, hour)`. Every `admin-api` replica may run the
     job; a repeated or concurrent run writes the same row, like the invoicing job (ADR-004).
   - **Off the delivery path:** it reads committed data once an hour. Accepting and sending a message do no extra work.
2. **A robust, seasonal baseline.** Each feature is compared with the same hour of the week over the tenant's last
   eight weeks, using the median and the median absolute deviation. One past spike does not move the baseline the way
   a mean and standard deviation would. A value is anomalous when its robust z-score passes a threshold **and** its
   absolute size passes a floor, so a tenant going from 2 to 9 messages is not flagged. A score of 4 or more is a
   `WARNING`, 8 or more is `HIGH`; both are configurable and tuned by the false-alarm rate. A country that is new to
   the tenant on SMS or WhatsApp is always `HIGH`.
3. **Cold start.** A tenant with fewer than two weeks of history is compared with fixed per-channel limits from
   configuration instead. Per-plan limits were considered and left out: plans describe pricing, not expected traffic,
   and one more column on every plan buys little for the two weeks a tenant is new.
4. **Anomaly records, `traffic_anomaly`.** One row per tenant, channel, hour and feature that crossed the threshold,
   with the expected value, the actual value and the score. An administrator sees why it was flagged, not just that it
   was. Each record has a status: `OPEN`, `CONFIRMED` or `FALSE_ALARM`, set by an administrator.
5. **Alert, never block.** Stage 1 does not stop sending. It exports the count of open anomalies by channel and
   severity to Prometheus, and an alert rule is added beside the ADR-024 rules, with the same synthetic-series tests.
   Automatic pausing, if wanted, is a later decision made on Stage 1's observed false-alarm rate.
6. **The dashboard.** The admin console gets:
   - an **Anomalies** list, filterable by tenant, channel and status, with confirm and false-alarm actions that are
     written to the audit log (ADR-019);
   - per tenant, a chart of actual against expected traffic for the last 14 days;
   - a **weekly report** per tenant: volume, failure rate, countries reached, and the anomalies raised and how they
     were resolved.

### Stage 2: machine learning, if it earns its place

After at least six weeks of Stage 1 in production and a meaningful number of administrator decisions, train an
unsupervised model (Isolation Forest is the first candidate) on the same hourly features. Run it in **shadow**: it
scores every hour and its results are stored, but it raises nothing. Compare both methods against the administrators'
`CONFIRMED` and `FALSE_ALARM` decisions.

Adopt the model only if it finds more confirmed anomalies at the same or a lower false-alarm rate. Otherwise drop it
and record the result here. If adopted, it stays in Java (Smile or Tribuo) inside `admin-api`, so there is no new
runtime, image or service to secure.

## Options considered

- **Machine learning from day one.** Rejected: there are no labelled anomalies to train or evaluate against, and an
  unexplained score is hard for an administrator to act on. Stage 1 creates the labels Stage 2 needs.
- **Fixed thresholds only** (more than N messages an hour). Simple, but one number cannot fit tenants whose normal
  hour ranges from ten messages to a million. Kept only for the cold start.
- **Scoring each message at acceptance.** Catches abuse sooner, but adds work to the accept path (ADR-008) and needs
  the address, which erasure removes. Hourly aggregates are enough to stop the damage while it is still small.
- **A Python service for the model.** The richest ecosystem, but one more runtime, image, SBOM and attack surface
  (ADR-027) for a model the JVM libraries already provide.
- **Running the job in the dispatcher.** It already runs scheduled jobs, but the dispatcher is the delivery path and
  the dashboard lives in `admin-api`.
- **A third-party fraud service.** Effective for SMS pumping, but it sends traffic metadata to another processor and
  adds per-message cost. Worth revisiting if pumping becomes a real loss.

## Consequences

Positive:

- Pumping, leaked keys and runaway senders are noticed within about an hour instead of at invoice time.
- Every alert explains itself: which feature, expected against actual.
- Administrators' decisions build the labelled data that makes a fair evaluation of machine learning possible.
- No change to the accept or send path, and no personal data in the new tables.

Negative / accepted:

- **Detection lags by up to an hour plus the job's run time.** Prepaid credit and postpaid spend
  caps (ADR-004) remain the hard limit in the meantime.
- **The job reads an hour of messages each run.** It needs an index on `notification_message (created_at)` if the
  existing ones do not serve it; to be confirmed with `EXPLAIN` on production-sized data (see `perf/`).
- **The job runs as `notification_app`,** which can write as well as read (ADR-012). A read-only role or a read replica
  for reporting would be stronger and is left as a follow-up.
- **Seasonal tenants trip it.** A tenant's first sale or month-end run looks like an anomaly. The false-alarm action
  is the release valve, and the rate it is used at is what tunes the threshold.
- **New tables, a job, an alert rule and a dashboard page** to maintain.

## Follow-ups

- A read-only database role, or a read replica, for reporting jobs.
- Tenant-facing anomaly notices in the client console, once false alarms are rare enough to show customers.
- Optional automatic pausing of a tenant's SMS above a high score, decided on Stage 1's data.
- The Stage 2 shadow evaluation and its recorded result.
