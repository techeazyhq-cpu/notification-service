# Enterprise architecture review

- **Date:** 2026-10-02
- **Baseline:** `main` at `dd9234a` (pull requests up to #76, ADR-001 to ADR-027)
- **Supersedes:** the first review (September 2026, PRs #1 to #6) and the 2026-10-01 re-review that listed seven
  production blockers. Finding IDs are kept from the first review so each one can be traced from finding to fix.

Findings cite evidence: an ADR, a file, a test, or a measurement. Ratings are judgements. Where a claim rests on a
measurement, the number and its source are given; where it rests on absence, the check that found nothing is stated.

## Scope and limits

Inspected: every module, the migrations, the CI and Security workflows, the Helm chart and PostgreSQL manifests, the
benchmarks and failure drills in [quality-attributes-analysis.md](quality-attributes-analysis.md), the drills in
[disaster-recovery.md](disaster-recovery.md), and the first run of the image publishing pipeline on `main`.

Not done: penetration testing; load tests on production-like hardware (throughput figures come from one laptop, and
the Kubernetes drills from k3s in Docker); a live run against a cloud provider or a real SMS or e-mail provider; legal
review of privacy obligations.

## Verdict

**Ready for a production pilot on Kubernetes, with a known list of limits.** All seven production blockers from the
2026-10-01 re-review are closed. Admin access is hardened, operational endpoints are off the public port, provider
calls cannot reach the internal network, and each request is traced end to end. Accepts and delivery are measured
against service level objectives with alerts. Deployment is a hardened Helm chart, and PostgreSQL recovery has
stated objectives and a tested restore. Releases are signed images with an SBOM.

What stops it from being called enterprise-ready without qualification:

- **No cloud environment exists yet.** The infrastructure code for AWS is the next piece of work, and until it is
  applied, the recovery objectives are proven on k3s, not in a cloud region.
- **Scale beyond one database primary is untested.** PostgreSQL is still the shared hot path (C1), and delivery tops
  out near 300 messages per second per dispatcher until topics are partitioned (C3).
- **The core is not yet clean architecture** (A1). This is a maintainability risk, not an operational one.
- **Gaps in the product surface:** no SSO for administrators, no scoped client credentials, no webhooks, scheduling
  or opt-out list (S1, S5, E1, P1).

| Dimension | First review | Now | Why it moved |
|---|---|---|---|
| Delivery reliability and fault tolerance | Strong | Strong | Dead letters recorded, lost `QUEUED` messages swept, failover drilled under load |
| Data durability and integrity | Adequate | Strong | Synchronous standby, point-in-time recovery, restore drill in CI, retention and erasure |
| Security | Weak | Good | Roles, enforced 2FA, audit log, least-privilege database roles, TLS, encrypted secrets, SSRF policy, network policies |
| Observability | Weak | Good | One trace per request, JSON logs, four SLOs with burn-rate alerts, backup alerts |
| Delivery pipeline and operations | Missing | Good | CI and security scans as required checks, Helm chart, signed images with an SBOM; no cloud IaC yet |
| Scalability | Weak | Adequate | 750 req/s ingest per instance (p99 273 ms), autoscaling; database and topics still single |
| Testing and quality gates | Weak | Adequate | Testcontainers in every module, drills, promtool and restore tests in CI; 63 % line coverage against an 80 % target |
| Compliance and privacy | Weak | Adequate | Retention, erasure, encrypted variables; no opt-out list or data residency yet |
| Clean architecture and DDD tactics | Weak | Weak | Ports and fitness functions in two modules; JPA entities are still the core domain model |
| API design | Adequate | Adequate | Unchanged; still no scoped credentials or contract tests |
| Extensibility | Adequate | Adequate | Unchanged; no webhooks or scheduling |

## Production blockers from the 2026-10-01 re-review

| # | Blocker | Closed by |
|---|---|---|
| 1 | No branch protection on `main` | Runbook and applied protection with required checks and no admin bypass (#66, `docs/branch-protection.md`) |
| 2 | No audit trail of administrator actions | Append-only audit log, including refused attempts and sign-ins (#67, ADR-019) |
| 3 | Admin 2FA and the initial-password change were only a UI banner | Enforced by the server before any other call is allowed (#68, ADR-020) |
| 4 | No tracing, correlation IDs, structured logs, SLOs or alerts | OpenTelemetry through Pulsar, `X-Trace-Id`, JSON logs (#71, ADR-023); SLOs and alerts (#72, ADR-024) |
| 5 | No deployment artefacts, HA or backups | Helm chart with network policies (#73, ADR-025); PostgreSQL HA, PITR and restore drill (#74, ADR-026) |
| 6 | Actuator metrics and Swagger open on public ports | Management port 9080/9081, admin API docs off by default (#69, ADR-021) |
| 7 | Provider URLs could target internal addresses | Destination policy in code (#70, ADR-022) and egress network policy that closes DNS rebinding (ADR-025) |

## Status of the first review's findings

**Closed:** fixed, with evidence. **Partly:** the risk is reduced and the remainder is stated. **Open:** unchanged.
The last column is the severity of what remains, for a production launch with real customer data.

### Security

| ID | Finding (first review) | Status | Evidence and what remains | Now |
|---|---|---|---|---|
| S1 | One static admin user, default password, no roles, lockout or audit | Partly | Database-backed admins with TOTP (ADR-005), enforced on the server (ADR-020); viewer, operator and admin roles (ADR-015); failed-attempt lockout (`AuthSettings`); refusal to start with shipped defaults (ADR-013); audit log (ADR-019). **Remains:** no SSO (OIDC or SAML), so joiners and leavers are managed by hand | Medium |
| S2 | Services connect as a database superuser | Closed | Migration job owns the schema; services have DML-only rights (ADR-012); same roles in the CloudNativePG manifest | — |
| S3 | No TLS; secrets as plain environment variables with defaults | Partly | TLS at the edge (ADR-016) and to PostgreSQL, Redis and Pulsar (ADR-017); secrets only from an existing Kubernetes Secret fed by a secrets manager (ADR-025). **Remains:** no mTLS between services or to datastores; network policies limit who can connect in the meantime | Low |
| S4 | Provider credentials and personal data in clear at rest | Partly | Provider secrets encrypted (ADR-013); message variables encrypted with AES-GCM (ADR-017). **Remains:** the recipient column relies on storage encryption; no key id in the envelope, so keys cannot be rotated without downtime | Medium |
| S5 | API keys have no scopes or expiry; rotation is immediate | Open | No scope or expiry column in the migrations | Medium |
| S6 | Actuator and Swagger unauthenticated on public ports | Closed | ADR-021; network policies admit management ports only from monitoring (ADR-025) | — |
| S7 | Provider URLs not restricted (SSRF) | Closed | ADR-022 in code; egress policy blocks private, loopback, link-local and CGNAT ranges (ADR-025) | — |
| S8 | No dependency or image scanning, SBOM or signing | Closed | Trivy for dependencies, secrets and images; CodeQL; Dependabot; signed images with a signed SPDX SBOM (ADR-027). **Remains:** nothing in a cluster yet refuses unsigned images | Low |

### Architecture

| ID | Finding (first review) | Status | Evidence and what remains | Now |
|---|---|---|---|---|
| A1 | JPA entities are the domain model; use cases use repositories directly | Partly | `notification-core` now has a `port` package (`MessagePublisher`, `RateLimiter`, `HostResolver`); billing is the reference with ports and a behaviour-rich model. **Remains:** the six aggregates in `notification-core/domain` (`NotificationRequest`, `NotificationMessage`, `Client`, `Template`, `ProviderConfig`, `RateLimitPolicy`) are still JPA entities with little behaviour, and 7 classes outside persistence code import Spring Data (`StatusQueryService`, four in admin-api's dead-letter and message views, `CoreConfig`, `RedisRateLimiter`) | Medium |
| A2 | No architecture fitness functions | Partly | ArchUnit rules in `billing` and `notification-core`. **Remains:** none in client-api, dispatcher or admin-api | Medium |
| A3 | Three deployables share one database and a large shared kernel | Open | Accepted trade-off of ADR-001; table ownership by context is by convention only | Low |
| A4 | Inline comments against the agreed rule | Partly | 22 in main Java code down to 11, in `SecurityConfig`, `DispatchService`, `RecipientValidator` and `MigrationProperties` | Low |

### Scalability

| ID | Finding (first review) | Status | Evidence and what remains | Now |
|---|---|---|---|---|
| C1 | PostgreSQL is the hot path; messages unpartitioned; status aggregated on read | Partly | Rows are deleted after 400 days (ADR-009), so the table is bounded. **Remains:** no partitioning, status still counted on read, dashboards still on the primary although CloudNativePG provides a read-only service | Medium |
| C2 | Bulk ingest synchronous; single-send p99 423 ms | Closed | Shorter write path (ADR-008): p99 47 ms at 500 req/s, 273 ms at 750 req/s on one instance; a 10,000-recipient bulk accepted in 0.8 to 1.5 s | — |
| C3 | ~163 msg/s delivery; single-partition topics; one Redis node | Partly | ~300 msg/s per dispatcher; dispatcher autoscaling 2 to 8 (ADR-025); Redis loss degrades to fail-open with an alert (ADR-024). **Remains:** topics single-partition, scaling on CPU rather than backlog | Medium |
| C4 | Per-instance circuit breakers; UIs poll every 3 to 5 s | Open | Unchanged; acceptable at present | Low |

### Reliability, durability, fault tolerance

| ID | Finding (first review) | Status | Evidence and what remains | Now |
|---|---|---|---|---|
| R1 | Dead letters never marked `FAILED`; `QUEUED` messages lost by the broker never recovered | Closed | Dead-letter recorder and bulk reprocessing (ADR-010); sweeper republishes long-`QUEUED` and `RETRYING` rows (ADR-026); crash drill delivered 1,200 of 1,200 | — |
| R2 | Every datastore single-node; no backups, PITR, drill or RPO/RTO | Closed | Three instances with a synchronous standby, PITR from archived WAL, restore drill in CI, stated objectives (ADR-026); Pulsar and Redis rebuildable from PostgreSQL. Primary crash under load: promoted in ~27 s, nothing lost. **Remains:** applying it in a cloud region (IaC) | Low |
| R3 | Rate limiter fails open silently | Closed | Errors counted and alerted (ADR-024) | — |
| R4 | A worker crash can send a message twice; providers get no idempotency key | Open | Crash drill: 1 duplicate in 1,200; recovery of stuck messages takes up to 5 minutes. At-least-once delivery is the documented contract | Medium |
| R5 | No broker health; no readiness or liveness split | Closed | Liveness and readiness on the management port, startup probe, rolling updates with disruption budgets (ADR-025). Readiness deliberately excludes dependencies so a database blip does not unroute every pod | — |

### Observability

| ID | Finding (first review) | Status | Evidence and what remains | Now |
|---|---|---|---|---|
| O1 | No tracing, correlation IDs, structured logs, SLOs or alerts | Closed | ADR-023 and ADR-024; backup and WAL archiving alerts in `postgres-recovery.rules.yml`; all rules unit-tested with `promtool` in CI. **Remains:** no spans for database or Redis calls; a republished message starts a new trace; no dashboards in the repository | Low |
| O2 | No audit trail for administrator actions | Closed | ADR-019 | — |

### Delivery, quality, compliance

| ID | Finding (first review) | Status | Evidence and what remains | Now |
|---|---|---|---|---|
| D1 | No CI/CD, IaC, Kubernetes manifests or environment separation | Partly | Required CI and Security checks; Helm chart validated in CI; signed images published from `main` and version tags (ADR-027). **Remains:** no infrastructure code for a cloud, no deployment stage or GitOps, so nothing promotes a release between environments | High |
| D2 | 41.6 % coverage; integration tests only for migrations | Partly | PostgreSQL integration tests (Testcontainers) in every Java module; chaos drills and benchmarks in `perf/`; restore drill and alert-rule tests in CI; 63 % line coverage overall. **Remains:** below the 80 % target (per-module gates 41 % to 89 %); no API contract tests; no automated end-to-end or load test in CI | Medium |
| P1 | Personal data in clear; no retention, erasure, consent or opt-out | Partly | Retention (90 days to erase, 400 to delete) and erasure on request (ADR-009); variables encrypted (ADR-017). **Remains:** no opt-out or suppression list checked at accept; data residency depends on the region chosen in the IaC | Medium |
| E1 | No webhooks, scheduled sends or localisation; a new channel touches many places | Open | Unchanged | Low |

## New findings

| ID | Severity | Finding | Recommendation |
|---|---|---|---|
| N1 | Medium | The publish job rebuilds images rather than promoting the scanned ones byte for byte, and base images are pinned by tag, not digest (ADR-027) | Pin base images by digest; Dependabot keeps them current |
| N2 | Medium | Signatures and SBOMs are produced but nothing enforces them at deploy time | Kyverno `verifyImages` or the Sigstore policy controller in each cluster, matching the identity in ADR-027 |
| N3 | Low | While the broker is down, each accept waits the 5 s publish timeout before returning | A circuit breaker on the publisher so accepts return at once and the sweeper publishes later |
| N4 | Low | The `Helm chart` check is in `branch-protection.json` but not yet applied to `main` | Re-run step 2 of `docs/branch-protection.md` |
| N5 | Closed | A captured client API request could be sent again, or altered, because the API key alone authenticated it (`Idempotency-Key` is optional and attacker-controlled) | Done: opt-in HMAC request signatures per tenant, with a timestamp window and single-use nonces, and administrators can require them (ADR-036). The client console no longer stores the API key or signing secret in browser storage |

## Roadmap

1. **Before the first cloud environment:** D1 (AWS infrastructure code, then a deployment stage), N2, N4.
2. **Before broad production traffic:** S1 SSO, S5 scoped and expiring client credentials, P1 opt-out list, R4
   provider idempotency keys and a shorter stuck-message timeout, D2 contract tests and coverage, N1.
3. **Scale and maturity:** C1 partitioning and a status read model on the read replica, C3 partitioned topics and
   backlog-based autoscaling (KEDA), A1 and A2 across the remaining modules, S4 recipient encryption and key
   rotation, E1 webhooks and scheduling.

## Conformance summary

| Principle | First review | Now | Notes |
|---|---|---|---|
| Enterprise architecture (bounded contexts, ADRs, published contracts) | Partly | Yes | 36 ADRs; each blocker and follow-up traced to a decision; shared database remains the accepted trade-off |
| Clean architecture | No (billing: yes) | Partly | Ports and fitness functions in billing and the core; entities still the core domain model (A1, A2) |
| Security | No | Yes, with gaps | S1 SSO, S4 recipient encryption, S5 scopes, N2 signature enforcement |
| Scalability | Partly | Partly | Stateless tiers autoscale; one database primary and single-partition topics (C1, C3) |
| Reliability and fault tolerance | Application only | Yes | Drilled at application and datastore level; at-least-once with rare duplicates (R4) |
| Durability | Partly | Yes | Zero data loss on primary failure, ≤ 5 min for corruption or region loss, tested restore |
| Operability | No | Yes, pending a cloud | Helm, SLOs, alerts, runbooks, signed releases; cloud IaC next (D1) |
| Extensibility | Partly | Partly | Provider SPI and templates; E1 |
