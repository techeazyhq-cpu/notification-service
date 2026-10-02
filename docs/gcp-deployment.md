# Deploying to Google Cloud

How to build one environment of the notification service on Google Cloud with
[`deploy/terraform/gcp`](../deploy/terraform/gcp) and install it. ADR-030 records the decisions behind it.

## What gets built

| Layer | Resource | Why this shape |
|---|---|---|
| Network | Custom VPC with a node subnet (secondary ranges for pods and services, flow logs, Private Google Access), Cloud NAT, and private services access for Cloud SQL and Memorystore | A zone outage removes a third of the capacity, nothing else |
| Kubernetes | Regional GKE on the regular channel: Dataplane V2 (network policies enforced), private nodes, private control plane unless authorized ranges are listed, Workload Identity, Shielded Nodes, a node service account with logging and monitoring roles only, Binary Authorization, security posture | The chart's default-deny network policies (ADR-025) need an enforcing data plane |
| PostgreSQL | Cloud SQL for PostgreSQL 16, regional (synchronous standby in another zone), private IP only, TLS required with a per-instance CA, point-in-time recovery, 30 daily backups stored in the recovery region, deletion protection, backups kept after deletion | Same objectives as CloudNativePG (ADR-026) without running the database ourselves |
| Redis | Memorystore for Redis, Standard tier with a replica, AUTH, TLS with its own CA, private services access | Rate limits only; nothing to back up (ADR-026) |
| Pulsar | Apache Pulsar Helm chart on GKE: three ZooKeeper, bookie and broker pods, one per zone, TLS on the broker | Google Cloud has no managed Pulsar, and the broker's contents are rebuilt from PostgreSQL |
| Backups | GCS bucket in the recovery region: uniform IAM access, public access prevention enforced, versioned, 30-day expiry; a daily logical dump CronJob writes to it | An independent copy beside the Cloud SQL backups |
| Secrets | Secret Manager, one entry per value of the chart's Secret, replicated to the primary and recovery regions only. Terraform writes the owner password write-only and the Redis AUTH string; an operator adds the rest. External Secrets syncs them | No secret in Git or the chart; the owner password never in Terraform state |
| Alerting | Cloud Monitoring alert policies for PostgreSQL down and storage above 85%, and Redis memory above 85%, to e-mail channels | The managed-service counterpart of `postgres-recovery.rules.yml` |

The services get no Google identity. Only External Secrets (read the secrets) and the backup job (create objects in
the bucket) can impersonate Google service accounts, each through one Kubernetes service account.

## Before the first apply

1. **Choose the regions.** The primary region holds recipients and message content, so choose it for data residency.
   The recovery region holds the database backups, the dumps and a replica of every secret.
2. **Create the state bucket** once per project: GCS with versioning, uniform access and public access prevention.
   Then write `deploy/terraform/gcp/production/backend.hcl` (ignored by Git):

   ```hcl
   bucket = "<state bucket>"
   prefix = "notification/prod"
   ```

3. **Variables:** write `production.auto.tfvars` (also ignored by Git) with `project_id`, `region` and
   `disaster_recovery_region`. To reach the Kubernetes API from outside the VPC, list your networks in
   `control_plane_authorized_cidrs`; leave it empty for a private endpoint reached through a bastion or VPN.

## Apply

From `deploy/terraform/gcp/production`, with application-default credentials of a project owner (or an identity with
the equivalent roles):

```bash
terraform init -backend-config=backend.hcl
```

```bash
terraform plan -out=production.tfplan
```

```bash
terraform apply production.tfplan
```

The first apply enables the required APIs and takes about 30 minutes. Then:

```bash
gcloud container clusters get-credentials notification-prod --region <region>
```

## Install the platform pieces

In this order, each from its standard Helm chart with versions you have tested:

1. **cert-manager** (Pulsar's TLS certificates).
2. **External Secrets Operator** into namespace `external-secrets`.
3. **ingress-nginx** into namespace `ingress-nginx`, behind a Google Cloud network load balancer.
4. **Prometheus** (kube-prometheus-stack) into namespace `monitoring`, with `notification-slo.rules.yml` loaded.
5. **Pulsar:**

   ```bash
   kubectl apply -f deploy/k8s/pulsar/storage-class-gcp.yaml
   ```

   ```bash
   helm install pulsar apache/pulsar --version 4.7.0 -n pulsar --create-namespace -f deploy/k8s/pulsar/values.yaml --set initialize=true
   ```

## Prepare the namespace

1. **Create the namespace** `notification`.
2. **Trust bundles.** Cloud SQL and Memorystore each sign their server certificates with a CA of their own; Terraform
   outputs both:

   ```bash
   terraform -chdir=deploy/terraform/gcp/production output -raw postgres_server_ca_certificate > ca-bundle.pem
   ```

   ```bash
   kubectl -n notification create configmap db-ca-bundle --from-file=ca-bundle.pem
   ```

   ```bash
   terraform -chdir=deploy/terraform/gcp/production output -raw redis_server_ca_certificate > ca.pem
   ```

   ```bash
   kubectl -n notification create configmap redis-ca --from-file=ca.pem
   ```

   Create `pulsar-ca` from the Pulsar CA as in [aws-deployment.md](aws-deployment.md#prepare-the-namespace). When
   Cloud SQL or Memorystore rotates its CA, refresh the ConfigMap before the rotation completes.
3. **The runtime database role (ADR-012).** Connect once as `notification` (password in the secret
   `notification-prod-migration-db-password`) and create `notification_app`, with the same statements as for AWS.
4. **Application secrets.** Add a version to each of `notification-prod-db-password` (the password just set),
   `-secrets-encryption-key`, `-data-encryption-key`, `-admin-two-factor-key` and `-admin-password`, generated as the
   README describes.
5. **Sync them into the cluster.** List the placeholders explicitly, so nothing else in the file is touched:

   ```bash
   eval "$(terraform -chdir=deploy/terraform/gcp/production output -json manifest_values | jq -r 'to_entries[] | "export \(.key)=\(.value)"')"
   ```

   ```bash
   envsubst '${PROJECT_ID} ${CLUSTER_LOCATION} ${CLUSTER_NAME} ${SECRET_PREFIX} ${EXTERNAL_SECRETS_SERVICE_ACCOUNT}' < deploy/k8s/gcp/external-secrets.yaml | kubectl apply -f -
   ```

## Install the service

```bash
terraform -chdir=deploy/terraform/gcp/production output -json helm_values > helm-values-prod.json
```

```bash
helm install notification deploy/helm/notification-service -n notification -f deploy/helm/notification-service/values-gcp.yaml -f helm-values-prod.json -f <hosts and TLS for this environment>
```

The cluster pulls the signed images from GHCR: make them public, or add a pull secret (ADR-027). Binary
Authorization runs with the project's policy; set it to require the ADR-027 signatures once that is wired up.

## Daily logical dump

```bash
kubectl -n notification create configmap notification-backup-scripts --from-file=deploy/backup/postgres-backup.sh
```

```bash
envsubst '${LOGICAL_BACKUP_SERVICE_ACCOUNT} ${POSTGRES_PRIVATE_IP} ${BACKUP_BUCKET} ${PRIVATE_SERVICES_CIDR}' < deploy/k8s/gcp/logical-backup.yaml | kubectl apply -f -
```

Run the restore drill against a dump from the bucket monthly ([disaster-recovery.md](disaster-recovery.md)).

## Recovery on Google Cloud

| Scenario | Recovery point | Recovery time | How |
|---|---|---|---|
| Primary database fails | None | Typically about 1 minute | Regional Cloud SQL fails over to the standby; the private IP does not change |
| Data destroyed by mistake | Seconds (transaction logs kept 7 days) | < 1 hour | Clone the instance to a point in time (`gcloud sql instances clone --point-in-time`), verify, point `config.database.url` at it, `helm upgrade` |
| Primary region lost | Up to a day (the last daily backup, stored in the recovery region), or the last dump | < 4 hours | Apply this stack in the recovery region, restore the latest backup into a new instance, install as above |
| Pulsar lost | None | ≤ 15 min of delay | Reinstall; the sweeper republishes from PostgreSQL |
| Redis lost | Not applicable | None | Memorystore fails over to its replica; the rate limiter fails open meanwhile |

Point-in-time recovery is limited to the 7 days of transaction logs Cloud SQL keeps for the Enterprise edition; older
states come from the 30 daily backups. The region-loss recovery point is the weakest of the three clouds, because
the backups that leave the region are daily. Where that is too much, add a cross-region read replica and promote it
(`gcloud sql instances promote-replica`); that brings it down to seconds.

## Costs to expect

The baseline is roughly: a regional GKE cluster with six `n2-standard-4` nodes, a regional `db-custom-4-16384` Cloud
SQL instance (billed for the standby too), a 5 GB Standard-tier Memorystore, Cloud NAT, Pulsar's SSD disks, and
backup and log storage. Check it with the Google Cloud pricing calculator for your regions.
