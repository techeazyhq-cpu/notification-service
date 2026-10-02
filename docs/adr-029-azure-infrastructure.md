# ADR-029: Azure as a second cloud, with Flexible Server, Managed Redis and Pulsar on AKS

- **Status:** Accepted
- **Date:** 2026-10-02

## Context

ADR-028 built the AWS environment. The owner also wants Azure and Google Cloud supported from the same repository,
so a customer or region that requires either is a Terraform apply away, not a project. This ADR covers Azure. Google
Cloud follows the same shape in its own ADR.

Two things in the service had assumed AWS's managed Redis, which accepts unauthenticated connections inside the
network. Azure Managed Redis has no anonymous access at all, and Memorystore (Google Cloud) signs its TLS certificate
with a private CA. Neither was configurable through the chart.

## Decision

1. **Same layout as AWS.**
   - `deploy/terraform/azure/modules/datastores`: PostgreSQL, Redis, Key Vault, the backup account, private
     endpoints and alerts. It is plan-tested with a mocked provider.
   - `deploy/terraform/azure/production`: one environment, adding the network, AKS and the workload identities.

   Resources are written directly against `azurerm` 5.x rather than through Azure Verified Modules, so every security
   setting is visible in this repository and checked by the tests and the scan.
2. **PostgreSQL: Flexible Server 16, zone-redundant HA.**
   - A synchronous standby in another zone, injected into a delegated subnet with no public access.
   - TLS 1.2 required, 35 days of point-in-time recovery, geo-redundant backups to the paired region.
   - A delete lock and `prevent_destroy`.
   - The administrator is the schema owner of ADR-012. Its password is generated as an ephemeral value and written
     write-only to both the server and Key Vault, so it never appears in Terraform state.
3. **Redis: Azure Managed Redis** with high availability, TLS only, the `EnterpriseCluster` policy (one endpoint
   behind a proxy, which the services' standalone client needs), a private endpoint, and access-key authentication.
4. **The chart learns Redis authentication and private CAs.**
   - `config.redis.passwordFromSecret` reads `REDIS_PASSWORD` from the Secret.
   - `config.redis.tlsTrustCertificateFile` turns on TLS that trusts only the given CA.

   Both reach Spring Boot through standard `SPRING_DATA_REDIS_*` and `SPRING_SSL_BUNDLE_*` variables, with no code
   change. A context test (`RedisConnectionSettingsTest`) pins that those exact variable names configure the
   connection. Both default to off, so AWS and local setups are unchanged.
5. **Pulsar on AKS** from the same values as on EKS. `deploy/k8s/pulsar/values-aws.yaml` becomes the cloud-neutral
   `values.yaml`, and each cloud provides a storage class named `pulsar-disk`.
6. **Private endpoints for everything else.** Redis, Key Vault and the backup account each have a private endpoint
   and a private DNS zone. The backup account accepts only Entra ID identities, with account keys and public access
   disabled.
7. **Identities:**
   - AKS uses workload identity, with an OIDC issuer and federated credentials bound to one service account each:
     External Secrets as *Key Vault Secrets User* on the vault, the backup job as *Storage Blob Data Contributor* on
     the dump container.
   - The services get no Azure identity.
   - Cluster access is Entra ID RBAC, with local accounts disabled.
8. **Network policies are enforced** by Azure CNI overlay with the Cilium data plane. Egress leaves through a
   zone-redundant NAT gateway.
9. **Alerting:** an action group receives PostgreSQL storage and availability metric alerts, plus Resource Health
   events for the database and the cache.
10. **CI:** the `Terraform` and `Helm chart` checks loop over the clouds.
    - Format, module tests, validate, and tflint with the azurerm ruleset.
    - The chart rendered with `values-azure.yaml`, and the Azure storage class and manifests validated.
    - Manifest placeholders are written `${NAME}` and substituted from an explicit list, so shell variables in the
      manifests (written `$NAME`) are never blanked.

## Recovery objectives on Azure

| Scenario | Recovery point | Recovery time |
|---|---|---|
| Primary database fails | None | 1 to 2 minutes |
| Data destroyed by mistake or corruption | ≤ 5 minutes | < 1 hour |
| Primary region lost | Up to about 1 hour (geo-backup lag), or the last daily dump | < 4 hours |

The region-loss recovery point is weaker than AWS's minutes, because geo-redundant backup copies are asynchronous and
Azure states up to an hour. A cross-region read replica closes that gap at the cost of a second server.

## Options considered

- **Azure Cache for Redis** instead of Managed Redis: on its way to retirement, so not a base for new work.
- **Microsoft Entra authentication for Redis and PostgreSQL** instead of passwords: stronger, but it needs token
  acquisition and refresh in the services, which is a code change for a later ADR.
- **Customer-managed keys** for PostgreSQL, Redis and storage: possible, but they need a key and an identity per
  region for geo-redundant backups. Platform-managed encryption at rest is the baseline; CMK is a follow-up where a
  customer requires it.
- **Azure Verified Modules** for AKS and networking: well maintained, but they hide many defaults behind their own
  variables. Direct resources keep the security settings reviewable here.
- **CloudNativePG on AKS:** as for AWS, running the system of record ourselves on a new cloud adds the most risk for
  the least gain.

## Consequences

Positive: the same service installs on Azure with the same chart, the same Pulsar values and the same recovery
drills. The Redis options make the chart portable to any managed cache that needs a password or a private CA.

Negative / accepted:

- **Not yet applied to a real subscription.** Everything is validated, linted and plan-tested; the first apply is the
  first live proof.
- **Terraform needs network access to the vault's data plane** (`operator_ip_ranges`), because it writes two secrets.
- **The Redis access key is in Terraform state,** as an attribute of the cache. Protect the state store accordingly,
  or move to Entra authentication later.
- **Manual steps** mirror AWS: the state store, the runtime database role, the application secrets, the cluster
  add-ons, and the CA bundles.

## Follow-ups

- Entra ID authentication for PostgreSQL and Redis.
- A cross-region read replica, where the region-loss recovery point must be minutes.
- The recovery-region environment, applied empty, and a GitOps deployment stage (as for AWS).
