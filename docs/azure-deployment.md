# Deploying to Azure

How to build one environment of the notification service on Azure with
[`deploy/terraform/azure`](../deploy/terraform/azure) and install it. ADR-029 records the decisions behind it.

## What gets built

| Layer | Resource | Why this shape |
|---|---|---|
| Network | Virtual network with subnets for nodes, PostgreSQL (delegated) and private endpoints; a zone-redundant NAT gateway for egress | A zone outage removes a third of the capacity, nothing else |
| Kubernetes | AKS Standard tier across three zones, Azure CNI overlay with Cilium (network policies enforced), workload identity, Entra ID RBAC with local accounts disabled, private API server unless authorized ranges are listed, Azure Policy, Container Insights | The chart's default-deny network policies (ADR-025) need an enforcing data plane |
| PostgreSQL | Flexible Server 16, zone-redundant high availability (synchronous standby in another zone), private in a delegated subnet, TLS 1.2 required, 35 days of point-in-time recovery, geo-redundant backups to the paired region, delete lock | Same objectives as CloudNativePG (ADR-026) without running the database ourselves |
| Redis | Azure Managed Redis with high availability, TLS only, access-key authentication, private endpoint | Rate limits only; nothing to back up (ADR-026) |
| Pulsar | Apache Pulsar Helm chart on AKS: three ZooKeeper, bookie and broker pods, one per zone, TLS on the broker | Azure has no managed Pulsar, and the broker's contents are rebuilt from PostgreSQL |
| Backups | Storage account in the recovery region: Entra-only (no account keys), private endpoint only, versioned, 30-day expiry; a daily logical dump CronJob writes to it | An independent copy beside the geo-redundant server backups |
| Secrets | Key Vault with RBAC, purge protection, deny-by-default network rules and a private endpoint. Terraform writes the schema owner's password write-only and the Redis key; an operator adds the rest. External Secrets syncs them | No secret in Git or the chart; the owner password never in Terraform state |
| Alerting | Action group (e-mail) receiving PostgreSQL storage and availability alerts and Azure Resource Health events for the database and the cache | The managed-service counterpart of `postgres-recovery.rules.yml` |

The services get no Azure identity. Only External Secrets (read the vault) and the backup job (write the dump
container) have workload identities, each federated with one service account.

## Before the first apply

1. **Choose the regions.** The primary region must have availability zones; it holds recipients and message content,
   so choose it for data residency. Use its paired region as the recovery region: geo-redundant PostgreSQL backups
   always go to the pair.
2. **Create the state store** once per subscription: a storage account with shared keys disabled, versioning and a
   private container. Then write `deploy/terraform/azure/production/backend.hcl` (ignored by Git):

   ```hcl
   resource_group_name  = "<state resource group>"
   storage_account_name = "<state account>"
   container_name       = "tfstate"
   key                  = "notification/prod.tfstate"
   ```

3. **Variables:** write `production.auto.tfvars` (also ignored by Git) with `subscription_id`, `location`,
   `disaster_recovery_location`, `cluster_admin_group_object_ids` and `operator_ip_ranges`. The operator ranges must
   include where Terraform runs: it writes two secrets into the vault, whose network rules deny everything else.

## Apply

From `deploy/terraform/azure/production`, signed in with `az login` as an identity that can create role assignments
(Owner, or Contributor plus User Access Administrator):

```bash
terraform init -backend-config=backend.hcl
```

```bash
terraform plan -out=production.tfplan
```

```bash
terraform apply production.tfplan
```

Then get cluster credentials (Entra ID sign-in, local accounts are disabled):

```bash
az aks get-credentials --name notification-prod --resource-group notification-prod
```

## Install the platform pieces

In this order, each from its standard Helm chart with versions you have tested:

1. **cert-manager** (Pulsar's TLS certificates).
2. **External Secrets Operator** into namespace `external-secrets`.
3. **ingress-nginx** into namespace `ingress-nginx`, behind an Azure Standard load balancer.
4. **Prometheus** (kube-prometheus-stack) into namespace `monitoring`, with `notification-slo.rules.yml` loaded.
5. **Pulsar:**

   ```bash
   kubectl apply -f deploy/k8s/pulsar/storage-class-azure.yaml
   ```

   ```bash
   helm install pulsar apache/pulsar --version 4.7.0 -n pulsar --create-namespace -f deploy/k8s/pulsar/values.yaml --set initialize=true
   ```

## Prepare the namespace

1. **Create the namespace** `notification`.
2. **Trust bundles.** Flexible Server certificates chain to DigiCert Global Root G2 and Microsoft RSA Root CA 2017; put
   both in one bundle:

   ```bash
   curl -fsSL https://cacerts.digicert.com/DigiCertGlobalRootG2.crt.pem -o ca-bundle.pem
   ```

   ```bash
   curl -fsSL "https://www.microsoft.com/pkiops/certs/Microsoft%20RSA%20Root%20Certificate%20Authority%202017.crt" | openssl x509 -inform der >> ca-bundle.pem
   ```

   ```bash
   kubectl -n notification create configmap db-ca-bundle --from-file=ca-bundle.pem
   ```

   ```bash
   kubectl -n pulsar get secret pulsar-ca-tls -o jsonpath='{.data.ca\.crt}' | base64 -d > ca.crt
   ```

   ```bash
   kubectl -n notification create configmap pulsar-ca --from-file=ca.crt
   ```

   Refresh the Pulsar CA ConfigMap when cert-manager renews it (90 days), or use trust-manager.
3. **The runtime database role (ADR-012).** Connect once as `notification` (password in the vault secret
   `migration-db-password`) and create `notification_app`, with the same statements as in
   [aws-deployment.md](aws-deployment.md#prepare-the-namespace).
4. **Application secrets.** Add five secrets to the vault: `db-password` (the password just set), and
   `secrets-encryption-key`, `data-encryption-key`, `admin-two-factor-key`, `admin-password` generated as the README
   describes.
5. **Sync them into the cluster.** List the placeholders explicitly, so nothing else in the file is touched:

   ```bash
   eval "$(terraform -chdir=deploy/terraform/azure/production output -json manifest_values | jq -r 'to_entries[] | "export \(.key)=\(.value)"')"
   ```

   ```bash
   envsubst '${KEY_VAULT_URI} ${EXTERNAL_SECRETS_CLIENT_ID}' < deploy/k8s/azure/external-secrets.yaml | kubectl apply -f -
   ```

   `kubectl -n notification get secret notification-secrets` must exist before the next step: the migration hook
   reads it.

## Install the service

```bash
terraform -chdir=deploy/terraform/azure/production output -json helm_values > helm-values-prod.json
```

```bash
helm install notification deploy/helm/notification-service -n notification -f deploy/helm/notification-service/values-azure.yaml -f helm-values-prod.json -f <hosts and TLS for this environment>
```

The cluster pulls the signed images from GHCR: make them public, or add a pull secret (ADR-027).

## Daily logical dump

```bash
kubectl -n notification create configmap notification-backup-scripts --from-file=deploy/backup/postgres-backup.sh
```

```bash
envsubst '${LOGICAL_BACKUP_CLIENT_ID} ${POSTGRES_FQDN} ${BACKUP_STORAGE_ACCOUNT} ${POSTGRES_SUBNET_CIDR} ${PRIVATE_ENDPOINT_SUBNET_CIDR}' < deploy/k8s/azure/logical-backup.yaml | kubectl apply -f -
```

Run the restore drill against a dump from the account monthly ([disaster-recovery.md](disaster-recovery.md)).

## Recovery on Azure

| Scenario | Recovery point | Recovery time | How |
|---|---|---|---|
| Primary database fails | None | Typically 1 to 2 minutes | Zone-redundant HA promotes the standby; the host name does not change |
| Data destroyed by mistake | ≤ 5 minutes | < 1 hour | Point-in-time restore into a new server (`az postgres flexible-server restore`), verify, point `config.database.url` at it, `helm upgrade` |
| Primary region lost | Up to about an hour (geo-backup replication lag); at worst the last daily dump | < 4 hours | Apply this stack in the paired region, geo-restore the server (`az postgres flexible-server geo-restore`), install as above |
| Pulsar lost | None | ≤ 15 min of delay | Reinstall; the sweeper republishes from PostgreSQL |
| Redis lost | Not applicable | None | Managed Redis fails over; the rate limiter fails open meanwhile |

The region-loss recovery point is weaker than on AWS: Azure replicates geo-redundant backups asynchronously and
documents a recovery point of up to an hour. Where that is too much, add a cross-region read replica (Flexible Server
supports one) and promote it instead of geo-restoring.

## Costs to expect

The baseline is roughly: an AKS Standard tier cluster with six `Standard_D4ds_v5` nodes, a zone-redundant
`GP_Standard_D4ds_v5` Flexible Server (billed twice for the standby) with geo-redundant backup storage, a
`Balanced_B5` Managed Redis, the NAT gateway, Pulsar's disks, Log Analytics ingestion and the private endpoints.
Check it with the Azure pricing calculator for your regions.
