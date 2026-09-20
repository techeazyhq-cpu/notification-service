# Enterprise architecture review

Reviewed as an independent enterprise reviewer would: against the code in this repository (PRs #1 to #6), the running local stack, and measurements taken during development. Findings cite evidence. Ratings are judgements; where a claim rests on a measurement, the number is given, and where it rests on absence, the check that found nothing is stated.

## Scope and limits

Inspected: all modules, migrations, Compose file, both UIs, tests, Sonar results, and the running system (queries against the live database, a 50,000-recipient load run, failure drills for the circuit breaker).

Not done: penetration testing, load tests on production-like hardware (all timings are a laptop running Docker with one instance of each service), review of any real provider integration (only Mailpit and a local HTTP catcher exist), legal review of privacy obligations.

## Verdict

The platform is a **sound, well-tested foundation for reliable message delivery**, and it is **not yet enterprise-ready**. Its delivery core (outbox, atomic claim, retry, circuit breaker, idempotency, content snapshot, guarded migrations) is stronger than most first versions. The gaps are concentrated in security hardening, operability, and the absence of a delivery pipeline, plus a domain and application layering that does not yet follow clean architecture.

| Dimension | Rating | One-line reason |
|---|---|---|
| Delivery reliability and fault tolerance (application level) | Strong | Outbox with sweeper, atomic claim, backoff, per-provider circuit breaker with failover, dead-letter topic |
| Data durability and integrity | Adequate | Transactional accept, snapshots, guarded migrations; no backups, retention or HA, and one gap in dead-letter handling |
| Extensibility | Adequate | Provider SPI and self-service templates are good; adding a channel touches several places; no webhooks or scheduling |
| API design | Adequate | Versioned, paged, idempotent, OpenAPI; no contract tests, no read-only credentials |
| Scalability | Weak | PostgreSQL is the single hot path; measured limits below; synchronous bulk ingest |
| Clean architecture and DDD tactics | Weak | JPA entities are the domain model, repositories leak into use cases, no fitness functions |
| Security | Weak | Static admin login with a default password, superuser database role, no TLS, plaintext provider secrets |
| Observability | Weak | No tracing or correlation IDs, one custom metric, no SLOs or alerts, no audit trail |
| Delivery pipeline and operations | Missing | No CI, no infrastructure as code, no HA topology, no backup or restore procedure |
| Testing and quality gates | Weak | 41.6 % coverage against an 80 % gate; integration tests exist only for migrations |
| Compliance and privacy | Weak | Personal data stored in clear with no retention, erasure or opt-out |

## What is strong

- **No lost accepts.** A request and all its messages commit as `PENDING` in one transaction; publishing flips them to `QUEUED`; a sweeper republishes stragglers and reclaims work from crashed workers.
- **No double sends in normal redelivery.** An atomic conditional update claims each message before sending.
- **Failure containment.** Transient errors back off exponentially; permanent errors fail fast; each provider sits behind its own circuit breaker; when every provider of a channel is open the channel's consumers pause and no retry attempt is spent. Drilled live: 30 messages survived a full gateway outage with none failed.
- **Content safety.** Requests snapshot template content at accept time, so edits and deletes cannot alter or break queued work. Verified with a 2,000-recipient bulk edited and deleted mid-flight.
- **Schema safety.** A dedicated migration job with previews, checksums, tagged rollback, an advisory lock, and services that refuse to start against the wrong schema.
- **Traceable decisions.** Three ADRs, a design document, static analysis at zero findings.

## Findings

Severity is for a production launch handling real customer data.

### Security

| ID | Severity | Finding and evidence | Recommendation |
|---|---|---|---|
| S1 | Critical | Admin access is one static HTTP Basic user held in memory. The default is `admin` / `admin` (`admin-api/application.yml`, README). No roles, no lockout, no rate limit on the login path, no audit of admin actions. | OIDC/SAML SSO with roles (viewer, operator, admin); fail start-up if the default password is present outside local profiles; audit log of every admin write. |
| S2 | High | All services connect to PostgreSQL as a **superuser** (`select rolsuper from pg_roles where rolname='notification'` returns true). A single injection or compromised pod owns the database and every tenant's data. | Two roles: the migration job owns the schema, services get DML-only rights on the tables they use. ADR-003 lists this as the next step. |
| S3 | High | No TLS anywhere (no TLS configuration found in Compose or any service). API keys and admin credentials cross the network in clear. Database and admin passwords are plain environment variables with dev defaults. | TLS at the edge and between services (mTLS in-cluster); secrets from a manager (Vault, cloud secret store), never defaults. |
| S4 | High | Provider credentials (SMTP password, gateway auth header) are stored as plaintext JSON (`provider_config.settings`) and are only masked when read through the API. Recipient addresses and template variables are plaintext at rest. | Envelope encryption for provider secrets (KMS-backed data key); column-level or disk encryption for personal data. |
| S5 | Medium | API keys have no scopes: one key can send, manage templates and read everything. No expiry. Rotation invalidates the old key immediately. The client UI keeps a sending key in the browser. | Scoped credentials (send, read, manage), expiry, dual-key rotation window, and OIDC-backed portal users mapped to a client. |
| S6 | Medium | `/actuator/prometheus` and Swagger UI are exposed without authentication on the public ports. | Separate management port on an internal network; disable Swagger outside development. |
| S7 | Medium | The HTTP provider posts to any URL an administrator configures (no allow-list). If admin access is compromised, the service can be pointed at internal addresses. | Egress allow-list or network policy; validate provider URLs against configured domains. |
| S8 | Medium | No dependency vulnerability scanning, image scanning, SBOM or image signing. | Add to the pipeline (see D1). |

### Architecture

| ID | Severity | Finding and evidence | Recommendation |
|---|---|---|---|
| A1 | High | The original modules do not follow clean architecture. JPA entities are the domain model and carry no behaviour (13 domain classes, almost no methods beyond accessors); state transitions live in repository `UPDATE` statements; nine application-layer classes import Spring Data repositories directly; `IngestService` mixes validation, persistence and publishing. | Introduce ports for persistence, pure domain models with behaviour, mapping in adapters. The billing module in this change is built that way as the reference; migrate `notification-core` incrementally. |
| A2 | High | No architecture fitness functions. The design document promises them; none exist (no ArchUnit test in any module). Module boundaries hold only by convention. | Dependency-rule tests per module in CI (added for billing in this change). |
| A3 | Medium | Three deployables share one database and a large shared kernel (`notification-core` holds domain, persistence and infrastructure). Acceptable now, but coupling will grow silently. | Split the kernel: a small domain model module, adapters separately; keep table ownership per context. |
| A4 | Low | 22 inline comments remain in main Java code, plus some in TypeScript, against the agreed rule of no comments. | Convert to Javadoc or naming; separate sweep PR. |

### Scalability

| ID | Severity | Finding and evidence | Recommendation |
|---|---|---|---|
| C1 | High | PostgreSQL is the hot path for everything. `notification_message` is unpartitioned with no retention (64,212 rows, 24 MB after light testing). Status is aggregated on read: measured about 150 requests per second per instance, p50 130 ms, when 20 clients poll one 50,000-recipient request. Dashboards run on the primary. | Time-partition messages with retention; a read model for status and dashboards (options analysed earlier: short cache first, projector later); read replica. |
| C2 | High | Bulk ingest is one synchronous transaction: 50,000 recipients took 17.2 s to accept, and single-send accept latency was p99 423 ms against the 100 ms target. | Asynchronous bulk ingestion through Pulsar; publish after commit without waiting for broker acknowledgement (the sweeper already covers failures). Profile before tuning. |
| C3 | Medium | Delivery drained 50,000 messages in 306 s (about 163 messages per second) with two consumers per channel. Pulsar topics are single-partition. One Redis node backs rate limiting. | Tune consumers, partition topics, Redis replication. |
| C4 | Medium | Circuit-breaker state is per instance; both UIs poll every 3 to 5 seconds. | Acceptable at present; revisit with the read model. |

### Reliability, durability, fault tolerance

| ID | Severity | Finding and evidence | Recommendation |
|---|---|---|---|
| R1 | High | Messages that end in the Pulsar dead-letter topic are never marked `FAILED`; they stay `QUEUED` forever because the sweeper only reclaims `PENDING` and `PROCESSING`. A message lost from the broker after `QUEUED` is also unrecoverable. | Consume the dead-letter topic and mark `FAILED`; sweep long-`QUEUED` rows. |
| R2 | High | Every stateful dependency is single-node: Pulsar standalone, PostgreSQL, Redis. No backups (one was taken by hand), no point-in-time recovery, no restore drill, no defined RPO or RTO. | HA topologies, automated backups with tested restores, written RPO and RTO. |
| R3 | Medium | The rate limiter fails open when Redis is unavailable, deliberately, but nothing alerts. | Metric and alert on limiter errors. |
| R4 | Medium | After a worker crash a message can be sent twice (5-minute reclaim window). The message id is sent to providers but SMTP does not use it as an idempotency key. | Use provider idempotency keys where supported; document at-least-once to clients. |
| R5 | Medium | Health endpoints cover database and Redis but not the broker; no readiness gating on Pulsar; no connection-pool bulkheads. | Broker health indicator; readiness/liveness split; pool sizing per workload. |

### Observability

| ID | Severity | Finding and evidence | Recommendation |
|---|---|---|---|
| O1 | High | No distributed tracing or correlation IDs (no trace or MDC use found in main code), unstructured logs, one custom metric (`notification.dispatch`), no SLOs, alerts or dashboards. | OpenTelemetry with the trace id carried in the Pulsar envelope; JSON logs; SLIs and alerts for accept latency, time to send, backlog, failure ratio. |
| O2 | Medium | No audit trail for administrative actions. | Append-only audit log (who, what, when, before and after). |

### Delivery, quality, compliance

| ID | Severity | Finding and evidence | Recommendation |
|---|---|---|---|
| D1 | High | No CI/CD pipeline (no workflow files), no infrastructure as code, no Kubernetes or Helm manifests, no environment separation. Everything was verified locally by hand. | Pipeline: build, unit and integration tests, Sonar gate, dependency and image scan, migration `validate` and `update-sql`, image publish; IaC for environments. |
| D2 | Medium | Coverage is 41.6 % against the 80 % quality gate. Integration tests exist only for the migration job. No contract tests, load tests or automated end-to-end tests. | Testcontainers suites for the send path, retry, sweeper and dead-letter flows; OpenAPI contract tests; a load test in CI. |
| P1 | High | Personal data (recipients, message variables) is stored in clear with no retention policy, erasure, consent or opt-out handling, and no data residency controls. | Retention job and partition drop, erasure API, opt-out list checked at accept, encryption at rest, documented lawful basis with the calling applications. |
| E1 | Medium | Extensibility gaps: no delivery receipts or webhooks to clients (they must poll), no scheduled sends, no template localisation, adding a channel touches the enum, validators, topics and both UIs. | Webhook delivery with signing and retries; channel registration through the provider SPI; scheduling. |

## Roadmap

1. **Before any shared environment:** S1, S2, S3, D1 (pipeline), R1, remove the default credentials.
2. **Before production traffic:** S4, S5, S6, O1, R2, P1, C2, D2 up to the coverage gate, A2 across all modules.
3. **Scale and maturity:** C1 read model and partitioning, A1 migration of the core to ports and behaviour-rich domain models, E1 webhooks and scheduling, C3 tuning, HA and DR drills.

## Conformance summary

| Principle | Conforms | Notes |
|---|---|---|
| Enterprise architecture (bounded contexts, ADRs, published contracts) | Partly | Contexts and decisions are documented; shared database and shared kernel are the trade-off |
| Clean architecture | No (billing: yes) | See A1, A2; billing is the reference implementation with a dependency-rule test |
| Security | No | S1 to S8 |
| Scalability | Partly | Stateless services scale out; the database does not; measured limits above |
| Reliability and fault tolerance | Yes at application level, no at infrastructure level | R1 to R5 |
| Durability | Partly | Transactional accept and guarded schema changes; no backup, HA or retention |
| Extensibility | Partly | Provider SPI and templates; E1 |
