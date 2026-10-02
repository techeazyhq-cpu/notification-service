# ADR-028: AWS as the first cloud, with managed PostgreSQL and Redis, and Pulsar on EKS

- **Status:** Accepted
- **Date:** 2026-10-02

## Context

The Helm chart (ADR-025) and the recovery design (ADR-026) are cloud-neutral. They stop where a cloud starts: no
network, cluster, managed database, cross-region backup store or secrets manager exists, so the recovery objectives
had only been proven on k3s. The architecture review lists this as finding D1. The owner chose AWS.

## Decision

1. **Terraform, in two parts.**
   - `deploy/terraform/aws/modules/datastores`: RDS PostgreSQL, ElastiCache, the KMS keys, the backup bucket in the
     recovery region, the application secret, and the RDS event topic. It is the part with the recovery and
     security properties, so it is the part with tests.
   - `deploy/terraform/aws/production`: one environment. It adds the VPC and EKS from the community
     `terraform-aws-modules`, and the pod identities. Another environment is a copy of this root with its own state
     and variables.

   State lives in S3 with native locking. Backend settings and variable values stay out of Git.
2. **PostgreSQL: RDS Multi-AZ** (a synchronous standby in another zone), PostgreSQL 16.
   - KMS-encrypted, TLS required (`rds.force_ssl`), never public, reachable only from the node security group.
   - 30 days of point-in-time recovery, with the automated backups replicated to the recovery region.
   - Deletion protection and a final snapshot.
   - The master user is the schema owner of ADR-012. Its password is managed and rotated by RDS in Secrets Manager,
     so it never enters Terraform state. The runtime role is created once by hand (docs/aws-deployment.md).
3. **Redis: ElastiCache for Valkey,** a primary and a replica in two zones with automatic failover, TLS required,
   encrypted at rest, no snapshots. It holds only rate-limit counters (ADR-026), and the services enable TLS with
   `SPRING_DATA_REDIS_SSL_ENABLED`.
4. **Pulsar on EKS** from the Apache Pulsar Helm chart (`deploy/k8s/pulsar/values-aws.yaml`):
   - three ZooKeeper, bookie and broker pods, spread one per zone, on encrypted gp3 volumes;
   - TLS on the broker port from a cert-manager CA;
   - no proxy, functions or bundled monitoring.

   AWS has no managed Pulsar, and ADR-026 established that the broker's contents are rebuilt from PostgreSQL. So a
   broker cluster we run ourselves costs us operations work, not durability.
5. **Backups in two independent forms.** RDS's own (point in time, replicated across regions), and a daily logical
   dump CronJob writing to a versioned, KMS-encrypted, TLS-only bucket in the recovery region. The dump survives a
   problem with the RDS backups themselves, and it feeds the restore drill.
6. **Secrets flow one way:** Secrets Manager, then External Secrets, then the chart's existing Secret. Terraform
   creates the application secret empty; an operator fills it. The services get no AWS role at all. Only the
   External Secrets controller (read two secrets) and the backup job (write one bucket) have EKS Pod Identities.
7. **Network policies are enforced.** The VPC CNI runs with network-policy enforcement on, without which ADR-025's
   default-deny would be silently ignored. Node security groups allow egress inside the VPC, and to the internet only
   on 443, 25, 465 and 587. The per-pod rules (ADR-022, ADR-025) decide which pods may use that and keep private
   ranges unreachable. Trivy's "unrestricted egress" check is ignored for the EKS module alone, with the reason next
   to it.
8. **Alerting:** RDS backup, failover, failure, recovery, low-storage and maintenance events go to a KMS-encrypted
   SNS topic. This is the managed-service counterpart of `postgres-recovery.rules.yml`, which keeps covering
   CloudNativePG.
9. **Verification in CI** (a new required check, `Terraform`):
   - `terraform fmt`;
   - the module's plan-time tests with mocked providers, which pin Multi-AZ, retention, cross-region replication,
     encryption, TLS, privacy of the bucket, key rotation, the event subscription and the input validation;
   - `terraform validate` of the production root;
   - tflint with the AWS ruleset.

   The Helm chart check also renders the chart with `values-aws.yaml`, renders the Pulsar chart with its AWS values,
   and validates the External Secrets and backup manifests. The repository's Trivy scan covers the Terraform.

## Recovery objectives on AWS

| Scenario | Recovery point | Recovery time |
|---|---|---|
| Primary database fails | None (synchronous standby) | 1 to 2 minutes, RDS Multi-AZ failover |
| Data destroyed by mistake or corruption | ≤ 5 minutes (RDS uploads transaction logs every 5 minutes) | < 1 hour |
| Primary region lost | Minutes, as far as the replicated backups have reached; at worst the last daily dump | < 4 hours |

The primary-failure recovery time is longer than CloudNativePG's measured 27 seconds. If one to two minutes of slower
accepts is too much, RDS Multi-AZ DB clusters (two readable standbys) fail over in under a minute.

## Options considered

- **Aurora PostgreSQL.** Faster failover and storage replicated across three zones, but a different storage engine
  from the one the integration tests run on, and higher baseline cost. A reasonable later step once load justifies
  it; nothing in the service depends on the difference.
- **CloudNativePG on EKS (as in ADR-026).** Already designed and tested, and no managed-service premium. But the
  database is the system of record, and running it ourselves on a first cloud deployment adds the most operational
  risk for the least gain. The manifests stay for clusters outside AWS.
- **Amazon MQ or MSK instead of Pulsar.** Neither speaks Pulsar's protocol. Replacing the broker means rewriting the
  publish and consume paths, the dead-letter handling and the drills (ADR-001, ADR-010).
- **StreamNative Cloud for Pulsar.** Managed Pulsar on AWS. Removes the broker operations but adds a vendor and data
  leaving the account. It can replace step 4 by changing `config.pulsar.url` and the trust bundle.
- **IRSA instead of Pod Identity.** Works, but needs an OIDC provider and per-role trust policies tied to the
  cluster. Pod Identity is simpler to bind and is what EKS now recommends.
- **A NAT gateway per environment rather than per zone.** Cheaper, but a zone outage would cut every provider call.
  Kept per zone for production; a variable change for others.

## Consequences

Positive: an environment can be built from code and rebuilt in another region. The recovery and security properties
that matter are checked on every change. No secret appears in Git, Terraform state or the chart.

Negative / accepted:

- **Not yet applied to a real account.** Everything is validated, linted and unit-tested at plan time, but the first
  apply will be the first proof. Run the restore drill and a failover test (`aws rds reboot-db-instance
  --force-failover`) before taking traffic.
- **Some steps are manual:**
  - the state bucket;
  - the runtime database role;
  - filling the application secret;
  - installing cert-manager, External Secrets, ingress-nginx and monitoring;
  - refreshing the Pulsar CA ConfigMap when it renews.

  docs/aws-deployment.md lists them in order. A GitOps tool (Argo CD or Flux) is the natural next step for the
  cluster add-ons.
- **Pulsar on EKS is ours to operate:** upgrades, bookie disk growth, ZooKeeper health.
- **No deployment pipeline yet.** CI publishes signed images (ADR-027); promoting them to this environment is still a
  `helm upgrade` by an operator.
- **Costs** are dominated by NAT gateways, nodes and the Multi-AZ database. docs/aws-deployment.md names the levers.

## Follow-ups

- A deployment stage or GitOps for the chart and the cluster add-ons, with image signature enforcement (Kyverno or
  the Sigstore policy controller) matching ADR-027.
- A recovery-region environment root, applied empty (VPC and EKS only), to cut the region-loss recovery time.
- CloudWatch alarms on RDS metrics (free storage, replica lag, CPU) next to the event subscription.
