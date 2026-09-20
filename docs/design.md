# Notification Service — Design

Status: v0.1 (walking skeleton implemented). Decision record: [ADR-001](adr-001-modular-monolith-pulsar-outbox.md).

## 1. Problem and goals

A generic, multi-channel notification platform used by internal applications ("clients"):

- **Channels:** Email, SMS, WhatsApp, App Push.
- **Client API:** REST for single and bulk sends (recipients in the JSON body or an uploaded CSV) and a REST API to query request status.
- **Admin application:** configuration (providers, templates, clients/API keys), dashboard, rate limiting.
- **Internally:** Apache Pulsar between acceptance and delivery.

Success criteria (targets, to be validated by load test, not measured yet):

| Attribute | Target |
|---|---|
| Accept latency (single send) | p99 < 100 ms, independent of provider speed |
| Bulk | 50k recipients per request accepted in seconds; delivery throughput bounded only by rate limits and provider |
| Durability | A `202` means the request survives any single-component crash |
| Delivery semantics | At-least-once from the broker, exactly-once *claim* per message (no double send in normal redelivery) |
| Availability of ingest | Ingest keeps accepting while a provider is down |

Out of scope for v0.1: delivery receipts/webhooks from providers, scheduled sends, user preferences/opt-out, multi-region, OIDC for admin.

## 2. Domain (DDD, strategic)

**Core domain:** reliable, rate-controlled *dispatch* of a message to a recipient over a channel, with a queryable lifecycle. **Supporting:** client/tenant management, templates, provider configuration, rate-limit policy. **Generic:** authentication, transport (Pulsar), actual channel gateways (SMTP, SMS/WhatsApp/push vendors).

Ubiquitous language:

| Term | Meaning |
|---|---|
| Client | A calling application with an API key and allowed channels |
| Request | One submission by a client: SINGLE (one recipient) or BULK (many, shared content) |
| Message | The delivery to one recipient of a request; the unit of state, retry and rate limiting |
| Template | Named content with `{{variable}}` placeholders, per channel |
| Provider | A configured way to deliver a channel (SMTP server, HTTP gateway); tried in priority order |
| Rate-limit policy | Token bucket (rate/s, burst) scoped to client API, client×channel, or global channel |

Bounded contexts (kept as packages/modules inside one codebase, see ADR):

1. **Ingestion** (Client API): authenticate, validate, persist, publish, answer status queries.
2. **Dispatch** (Dispatcher): consume, rate-limit, render, send, retry.
3. **Administration** (Admin API + UI): clients, templates, providers, policies, dashboards.

Relationships: Administration is *upstream* (customer-supplier) to Ingestion and Dispatch for configuration; Ingestion → Dispatch via Pulsar (published language: a message id envelope). The three share one PostgreSQL schema today (a pragmatic *shared kernel*: `notification-core`); the schema is partitioned by table ownership so it can be split later.

Key domain events (implicit today, carried as state transitions): `RequestAccepted`, `MessageQueued`, `MessageSent`, `MessageRetryScheduled`, `MessageFailed`.

### Message lifecycle

```mermaid
stateDiagram-v2
    [*] --> PENDING: accepted (DB commit)
    PENDING --> QUEUED: published to Pulsar
    QUEUED --> PROCESSING: worker claims (atomic)
    RETRYING --> PROCESSING: redelivered after backoff
    PROCESSING --> SENT: provider 2xx
    PROCESSING --> RETRYING: transient error, attempts < max
    PROCESSING --> FAILED: permanent error / attempts exhausted
    PROCESSING --> QUEUED: worker died; sweeper republishes
    FAILED --> PENDING: admin retry
```

Request status is **derived** (`PROCESSING`, `COMPLETED`, `PARTIALLY_FAILED`, `FAILED`) from message counts, so 50k workers never contend on one request row.

## 3. Architecture

```mermaid
C4Container
    title Notification Service — containers
    Person(client, "Client application", "Calls REST with X-API-Key")
    Person(admin, "Administrator", "Uses the admin UI")
    System_Boundary(ns, "Notification Service") {
        Container(ui, "Admin UI", "React SPA", "Dashboard, config")
        Container(adminapi, "Admin API", "Spring Boot", "Clients, templates, providers, policies, stats")
        Container(clientapi, "Client API", "Spring Boot", "Single/bulk send, status")
        Container(dispatcher, "Dispatcher", "Spring Boot workers", "Consume, rate limit, render, send, retry")
        ContainerDb(pg, "PostgreSQL", "Relational", "Requests, messages, config")
        ContainerDb(redis, "Redis", "Token buckets", "Distributed rate limiting")
        ContainerQueue(pulsar, "Apache Pulsar", "One topic per channel (+retry, DLQ)")
    }
    System_Ext(smtp, "SMTP / SMS / WhatsApp / Push gateways")
    Rel(client, clientapi, "REST", "HTTPS + API key")
    Rel(admin, ui, "Uses")
    Rel(ui, adminapi, "REST", "HTTPS + Basic")
    Rel(clientapi, pg, "Persist request+messages (PENDING)")
    Rel(clientapi, pulsar, "Publish message ids")
    Rel(clientapi, redis, "API rate limit")
    Rel(dispatcher, pulsar, "Consume (Shared subscription)")
    Rel(dispatcher, pg, "Claim, read content, record outcome")
    Rel(dispatcher, redis, "Delivery rate limit")
    Rel(dispatcher, smtp, "SMTP / HTTPS")
    Rel(adminapi, pg, "Config + stats")
    Rel(adminapi, pulsar, "Re-queue failed")
```

A draw.io version of this view is in [`container.drawio`](container.drawio).

### 3.1 Send flow

1. `POST /v1/notifications[...]` → `ClientAuthFilter` hashes the key, loads the client (30 s cache), applies the CLIENT_API token bucket (`429` + `Retry-After` when exceeded).
2. `IngestService` validates channel permission, content (template or inline), recipients (E.164 / email / token), idempotency key.
3. One DB transaction inserts the request and every message as `PENDING` (JDBC-batched).
4. After commit, ids are published to `persistent://public/default/notification-<channel>`, keyed by client id; confirmed ones flip to `QUEUED`. Publish failures leave rows `PENDING`.
5. Response `202` with `requestId`. The `OutboxSweeper` republishes `PENDING` rows older than 30 s and `PROCESSING` rows older than 5 min (crashed worker).
6. Dispatcher consumer: load message → check rate limit (blocks up to 60 s as backpressure, then hands the message back) → atomic `claim` (`UPDATE … WHERE status IN (PENDING,QUEUED,RETRYING)`) → render → provider(s) in priority order → `SENT`.
7. Transient error → `RETRYING` + Pulsar `reconsumeLater` with exponential backoff (5 s × 2ⁿ, cap 5 min). After 5 attempts or on a permanent error → `FAILED`. Unexpected exceptions are negative-acked and end in the Pulsar dead-letter topic after `maxAttempts + 2` redeliveries.

Only the **message id** travels through Pulsar. Recipient addresses and variables stay in PostgreSQL, which keeps personal data out of the broker, its retry/DLQ topics and its backups (erasure = delete rows).

### 3.2 Rate limiting

Token buckets in Redis, evaluated in one Lua script using Redis server time (no clock-skew between nodes). Three scopes, all admin-managed and cached for 10 s:

- `CLIENT_API` — requests/s a client may make (default 50/s, burst 100 when unset).
- `CLIENT_CHANNEL` — messages/s delivered for a client on a channel (fairness between tenants).
- `GLOBAL_CHANNEL` — messages/s platform-wide per channel (protect the provider quota).

Delivery limits apply in the dispatcher, so a bulk request is *accepted* fast and *drained* at the allowed rate. The limiter fails open on Redis errors (availability over strictness); change if you would rather fail closed.

### 3.3 Providers

`ChannelProvider` SPI with two implementations: `SmtpProvider` and `HttpJsonProvider` (generic JSON gateway). Configs live in the DB and are edited in the admin UI. Locally, Mailpit captures email and `tools/catcher` captures SMS/WhatsApp/Push. Real vendors (Twilio, Meta WhatsApp Cloud API, FCM/APNs, SES) are added as new `ChannelProvider` beans or through `HttpJsonProvider` when the gateway accepts our JSON.

## 4. API contract (Client API)

OpenAPI is served at `/v3/api-docs`, Swagger UI at `/swagger-ui.html`. All calls need `X-API-Key`.

| Method | Path | Purpose |
|---|---|---|
| POST | `/v1/notifications` | Single send. `202` + `requestId`, `messageIds` |
| POST | `/v1/notifications/bulk` | Recipients in JSON body, per-recipient `variables` |
| POST | `/v1/notifications/bulk/upload` | `multipart/form-data`: CSV `file` (header row, `recipient` column, other columns = variables) + `channel`, `templateName`/`subject`/`body` |
| GET | `/v1/notifications/{requestId}` | Status + counts per message status |
| GET | `/v1/notifications/{requestId}/messages?status=&page=&size=` | Per-recipient outcome |
| GET | `/v1/notifications?page=&size=` | The client's recent requests |

- Send an `Idempotency-Key` header to make retries safe (per client; a replay returns `200` with the original `requestId`).
- Content is either `templateName` or inline `body` (+ `subject` for email). Strict template rendering: a missing variable fails that message permanently.
- Errors: `{ "code": "...", "message": "..." }` with `400`, `401`, `403`, `413`, `429`, `500`.

Example:

```bash
curl -X POST localhost:8080/v1/notifications \
  -H "X-API-Key: $KEY" -H "Idempotency-Key: order-42-shipped" -H "Content-Type: application/json" \
  -d '{"channel":"SMS","recipient":"+14155550123","templateName":"otp-sms","variables":{"code":"481516"}}'
```

## 5. Data

PostgreSQL (Flyway `V1__init.sql`): `client`, `template`, `provider_config`, `rate_limit_policy`, `notification_request`, `notification_message`. Notable choices: UUID primary keys assigned by the app (JDBC batching without a round trip); partial unique index for idempotency keys; indexes on `(request_id, status)` for status counts and `(status, updated_at)` for the sweeper. API keys are stored only as SHA-256 hashes (keys are 256-bit random, so an unsalted hash suffices for lookup).

## 6. Cross-cutting

**Security.** API key per client (hashed, rotatable, disable takes effect within 30 s); admin uses HTTP Basic with one configured user (v0.1 only — replace with OIDC and roles before any shared deployment); provider secrets are masked in admin responses. Threats considered (STRIDE-lite): spoofed client (key), tampering with content (only via authenticated API), repudiation (request/message rows are the audit trail), information disclosure (no PII in Pulsar or logs; recipients visible only to the owning client and admins), DoS (per-client API limit, bulk cap of 50k, upload cap of 20 MB), elevation (admin endpoints separated by port/service and role).

**Observability.** Actuator health and Prometheus metrics on every service; dispatcher counter `notification.dispatch{channel,outcome}` (`sent`, `retry`, `rate_limited`, `failed_permanent`, `failed_exhausted`). Recommended SLIs: accept success rate and latency; time from `PENDING` to `SENT` (p95); backlog size; FAILED ratio per channel. Add trace-id propagation into the Pulsar envelope next.

**Resilience.** Broker down → ingest still returns `202` (rows stay `PENDING`, sweeper catches up). Provider down → retries with backoff, then failover to the next provider, then `FAILED` with admin re-queue. Worker crash → sweeper reclaims after 5 min. Redis down → limiter fails open. Poison messages → DLQ topic.

**Compliance.** Recipient data is personal data: keep it in one store, add a retention job (delete messages after N days) and per-client erasure before production; document lawful basis and opt-out handling with the calling applications (this service sends what it is asked to). Not legal advice — confirm with your DPO.

## 7. Options considered (summary; detail in the ADR)

| Option | Complexity | Fit for drivers | Verdict |
|---|---|---|---|
| **A. Modular monolith library + 3 deployables (chosen)** | Low–medium | Independent scaling of ingest vs delivery, one schema, one team | Chosen |
| B. Microservice per context (templates, providers, limits, delivery…) | High | Needless network hops and distributed transactions for a small domain | Rejected for now |
| C. Single process, no broker | Lowest | Loses backpressure and independent scaling; contradicts the Pulsar requirement | Rejected |

## 8. Risks

| Risk | L | I | Mitigation |
|---|---|---|---|
| Duplicate sends after worker crash mid-send | M | M | Atomic claim + 5-min reclaim; documented at-least-once; provider idempotency keys where supported |
| Hot Postgres under large bulk | M | H | JDBC batching; derived request status; partition `notification_message` by month and add retention before production |
| Slow provider stalls a consumer thread | M | M | Timeouts (5 s connect / 10 s read); consumers per channel are independent; scale `consumers-per-channel` |
| Rate limiter outage | L | M | Fails open; alert on Redis errors |
| Admin auth too weak | H (if exposed) | H | Bind admin to private network; replace Basic with OIDC (ADR follow-up) |
| Bulk cap per request (50k) surprises clients | M | L | Documented; add async file ingestion with fan-out through Pulsar for larger lists |

## 9. Next steps

1. Testcontainers integration tests (Postgres + Redis + Pulsar) for the send → retry → DLQ → sweeper paths.
2. Real vendor adapters (SES/Twilio/FCM) behind `ChannelProvider`; provider delivery receipts → `DELIVERED` state.
3. Retention + partitioning of `notification_message`; erasure API.
4. OIDC for admin (roles: viewer/operator/admin); audit log of admin changes.
5. Trace propagation; SLO dashboards and alerts; load test against the targets in §1.
6. Async bulk ingestion for files above the synchronous cap.
