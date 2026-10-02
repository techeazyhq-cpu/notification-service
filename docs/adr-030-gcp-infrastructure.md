# ADR-030: Google Cloud as a third cloud, with Cloud SQL, Memorystore and Pulsar on GKE

- **Status:** Accepted
- **Date:** 2026-10-02

## Context

ADR-028 (AWS) and ADR-029 (Azure) build the service's environment on two clouds. Google Cloud completes the set the
owner asked for. ADR-029 already made the chart portable to managed caches that need a password or a private CA,
and made the Pulsar values cloud-neutral, so this ADR adds only what is specific to Google Cloud.

## Decision

1. **Same layout as AWS and Azure.**
   - `deploy/terraform/gcp/modules/datastores`: Cloud SQL, Memorystore, Secret Manager, the backup bucket and alert
     policies. It is plan-tested with a mocked provider.
   - `deploy/terraform/gcp/production`: one environment, adding the required APIs, the VPC, GKE and Workload
     Identity.

   Resources are written directly against `google` 8.x rather than the community modules, for the same reviewability
   as on Azure.
2. **PostgreSQL: Cloud SQL 16, regional** (a synchronous standby in another zone).
   - Private IP only, through private services access. TLS is required (`ENCRYPTED_ONLY`).
   - Point-in-time recovery with 7 days of transaction logs. 30 daily backups stored in the recovery region.
   - Deletion protection at both Terraform and API level, backups kept after deletion, and the logging flags the
     scan asks for.
   - The owner of ADR-012 gets an ephemeral, write-only password, mirrored write-only into Secret Manager.
3. **TLS verification against a per-instance CA.**
   - Cloud SQL's server certificate names the instance, not the private IP the services connect to, so
     `sslmode=verify-full` cannot succeed.
   - The instance uses `GOOGLE_MANAGED_INTERNAL_CA`, whose CA signs only this instance's certificates. The services
     use `sslmode=verify-ca` with that CA, and only this server can present a certificate that chains to it.
   - That gives the protection hostname verification gives elsewhere. The CA comes from a Terraform output.
4. **Redis: Memorystore, Standard tier** with one replica, AUTH, and `SERVER_AUTHENTICATION` TLS on private services
   access. Its CA is private, so the chart's `config.redis.tlsTrustCertificateFile` (ADR-029) trusts exactly it, and
   the AUTH string reaches the services through `REDIS_PASSWORD`.
5. **Secret Manager entries are replicated to the primary and recovery regions only**, keeping data residency while
   surviving a regional loss.
6. **GKE:**
   - Regional on the regular channel, with Dataplane V2 (Cilium) so network policies are enforced.
   - Private nodes, and a private control plane unless authorized ranges are given.
   - Workload Identity, Shielded Nodes, Binary Authorization in project-policy mode, and a basic security posture.
   - A node service account with logging and monitoring roles only.
   - Egress through Cloud NAT.
7. **Workload Identity:** one Google service account per workload, impersonable only by its Kubernetes service
   account in the release namespace. External Secrets may read only this environment's secrets, and the backup job
   may only create objects in the bucket. The services get no Google identity.
8. **Alerting:** Cloud Monitoring policies for PostgreSQL down and storage above 85%, and Redis memory above 85%,
   sent to e-mail channels.
9. **CI:** `gcp` joins `CLOUDS`. Module tests, validate and tflint with the google ruleset run on every change. The
   chart is rendered with `values-gcp.yaml`, and the GCP storage class and manifests are validated.

## Recovery objectives on Google Cloud

| Scenario | Recovery point | Recovery time |
|---|---|---|
| Primary database fails | None | About 1 minute |
| Data destroyed by mistake or corruption | Seconds, within the 7-day log window | < 1 hour |
| Primary region lost | Up to a day (daily backups), or the last dump | < 4 hours |

The region-loss recovery point is the weakest of the three clouds, because the backups that leave the region are
daily. A cross-region read replica, promoted on a regional loss, brings it to seconds at the cost of a second
instance. It is the first follow-up for any environment that needs a tighter objective.

## Options considered

- **Cloud SQL Enterprise Plus:** up to 35 days of logs and near-zero-downtime maintenance, at a higher price. The
  `edition` setting switches it.
- **The Cloud SQL Auth Proxy or Java connector,** which verify the instance identity themselves. Either means a
  sidecar per pod or a code change. verify-ca against a per-instance CA gives the same guarantee with the plain JDBC
  driver.
- **Memorystore for Valkey:** newer and cluster-oriented, but the services use a standalone client, which the Redis
  Standard tier serves directly.
- **Customer-managed encryption keys:** possible for every datastore here. Google-managed encryption at rest is the
  baseline; CMEK is a follow-up where a customer requires it.
- **The community `terraform-google-modules`:** well maintained, but they hide many defaults. Direct resources keep
  the security settings reviewable.

## Consequences

Positive: the service installs on any of the three clouds with one chart, one set of Pulsar values and one recovery
drill. Each cloud's environment is plan-tested, validated, linted and scanned on every change.

Negative / accepted:

- **Not yet applied to a real project.** The first apply is the first live proof.
- **The Redis AUTH string is in Terraform state,** as an attribute of the instance. Protect the state bucket
  accordingly.
- **Two CA ConfigMaps to keep fresh:** Cloud SQL and Memorystore rotate their CAs, so the ConfigMaps must be
  refreshed before each rotation completes.
- **Manual steps** mirror the other clouds.

## Follow-ups

- A cross-region read replica for environments that need a region-loss recovery point of seconds.
- Binary Authorization policy requiring the ADR-027 signatures.
- The recovery-region environment, applied empty, and a GitOps deployment stage.
