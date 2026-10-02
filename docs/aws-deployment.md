# Deploying to AWS

How to build one environment of the notification service on AWS with
[`deploy/terraform/aws`](../deploy/terraform/aws) and install it. ADR-028 records the decisions behind it.

## What gets built

| Layer | Resource | Why this shape |
|---|---|---|
| Network | VPC across three zones: private subnets for nodes, isolated subnets for the database and cache, public subnets for load balancers, one NAT gateway per zone, flow logs | A zone outage removes a third of the capacity, nothing else |
| Kubernetes | EKS with one managed node group (3 to 12 nodes), private API endpoint by default, all control-plane logs, the VPC CNI with network-policy enforcement on, the EBS CSI driver, EKS Pod Identity | The chart's default-deny network policies (ADR-025) only work if the CNI enforces them |
| PostgreSQL | RDS for PostgreSQL 16, Multi-AZ (synchronous standby), gp3 with autoscaling, KMS-encrypted, TLS required, 30 days of point-in-time recovery, automated backups replicated to the recovery region, deletion protection | Same objectives as CloudNativePG (ADR-026) without running the database ourselves |
| Redis | ElastiCache (Valkey 8), primary plus replica in another zone, automatic failover, TLS required, encrypted at rest | Rate limits only; nothing to back up (ADR-026) |
| Pulsar | Apache Pulsar Helm chart on EKS: three ZooKeeper, bookie and broker pods, one per zone, TLS on the broker | AWS has no managed Pulsar, and the broker's contents are rebuilt from PostgreSQL |
| Backups | S3 bucket in the recovery region: versioned, KMS-encrypted, TLS-only, never public, 30-day expiry; a daily logical dump CronJob writes to it | An independent copy that survives the primary region and the RDS account backups |
| Secrets | Secrets Manager: one entry for the application's values (created empty), and the schema owner's password managed and rotated by RDS; External Secrets syncs both into the chart's Secret | No secret in Terraform state, Git or the chart |
| Alerting | SNS topic receiving RDS backup, failover, failure, recovery, low-storage and maintenance events | The managed-service counterpart of `postgres-recovery.rules.yml` |

The services get no AWS permissions at all. Only External Secrets (read the two secrets) and the backup job (write to
the backup bucket) have pod identities.

## Before the first apply

1. **Choose the two regions.** The primary region holds recipients and message content, so choose it for data
   residency. The recovery region holds the replicated backups and dumps.
2. **Create the state bucket** once per account, by hand or from a bootstrap stack: S3 with versioning,
   encryption and public access blocked. Then write `deploy/terraform/aws/production/backend.hcl` (it is ignored by
   Git):

   ```hcl
   bucket = "<state bucket>"
   key    = "notification/production/terraform.tfstate"
   region = "<primary region>"
   ```

3. **Variables:** write `production.auto.tfvars` (also ignored by Git) with at least `region` and
   `disaster_recovery_region`. To reach the Kubernetes API from outside the VPC, list your networks in
   `cluster_endpoint_public_access_cidrs`; leave it empty to keep the endpoint private and connect through a VPN or
   bastion.

## Apply

Run from `deploy/terraform/aws/production`, from the pipeline or an operator workstation with an administrator role:

```bash
terraform init -backend-config=backend.hcl
```

```bash
terraform plan -out=production.tfplan
```

```bash
terraform apply production.tfplan
```

Review the plan before applying. The first apply takes about 30 minutes, mostly EKS and RDS. Then:

```bash
aws eks update-kubeconfig --name notification-production --region <primary region>
```

## Install the platform pieces

In this order. Each is a standard Helm chart; pin the versions you have tested.

1. **cert-manager** (Pulsar's TLS certificates).
2. **External Secrets Operator** into namespace `external-secrets`, with its default service account name
   `external-secrets`. Terraform has already bound the pod identity to it.
3. **ingress-nginx** behind an AWS Network Load Balancer, into namespace `ingress-nginx` (the chart's network policies
   admit public traffic from there).
4. **Prometheus** (kube-prometheus-stack) into namespace `monitoring`, with `notification-slo.rules.yml` loaded.
5. **Pulsar:**

   ```bash
   kubectl apply -f deploy/k8s/pulsar/storage-class.yaml
   ```

   ```bash
   helm install pulsar apache/pulsar --version 4.7.0 -n pulsar --create-namespace -f deploy/k8s/pulsar/values-aws.yaml --set initialize=true
   ```

## Prepare the namespace

1. **Create the namespace** `notification`.
2. **Trust bundles.** The services verify the database's and the broker's certificates. Create two ConfigMaps:

   ```bash
   curl -fsSL -o global-bundle.pem https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem
   ```

   ```bash
   kubectl -n notification create configmap rds-ca-bundle --from-file=global-bundle.pem
   ```

   ```bash
   kubectl -n pulsar get secret pulsar-ca-tls -o jsonpath='{.data.ca\.crt}' | base64 -d > ca.crt
   ```

   ```bash
   kubectl -n notification create configmap pulsar-ca --from-file=ca.crt
   ```

   The Pulsar CA is self-signed by cert-manager and renewed every 90 days. Refresh the ConfigMap when it renews,
   or replace both steps with cert-manager's trust-manager.
3. **The runtime database role (ADR-012).** RDS creates only the schema owner, `notification`. Connect once as the
   owner (its password is in the secret named by `terraform output postgres_owner_secret_arn`) and create the role
   the services use:

   ```sql
   CREATE ROLE notification_app LOGIN PASSWORD '<generated password>';
   GRANT USAGE ON SCHEMA public TO notification_app;
   ALTER DEFAULT PRIVILEGES FOR ROLE notification IN SCHEMA public
     GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO notification_app;
   ALTER DEFAULT PRIVILEGES FOR ROLE notification IN SCHEMA public
     GRANT USAGE, SELECT ON SEQUENCES TO notification_app;
   ```

4. **Application secrets.** Put one JSON object in the secret named by `terraform output application_secret_arn`,
   with the keys `DB_PASSWORD` (the password above), `SECRETS_ENCRYPTION_KEY`, `DATA_ENCRYPTION_KEY`,
   `ADMIN_TWO_FACTOR_KEY` and `ADMIN_PASSWORD`. Generate the keys as the README describes, never reuse local ones.
5. **Sync them into the cluster:**

   ```bash
   export AWS_REGION=<primary region> APPLICATION_SECRET_ARN=<from terraform output> POSTGRES_OWNER_SECRET_ARN=<from terraform output>
   ```

   ```bash
   envsubst < deploy/k8s/aws/external-secrets.yaml | kubectl apply -f -
   ```

   `kubectl -n notification get secret notification-secrets` must exist before the next step: the migration hook
   reads it.

## Install the service

```bash
terraform -chdir=deploy/terraform/aws/production output -json helm_values > helm-values-production.json
```

```bash
helm install notification deploy/helm/notification-service -n notification -f deploy/helm/notification-service/values-aws.yaml -f helm-values-production.json -f <hosts and TLS for this environment>
```

Install the release as `notification`: the alerts match its service names. Pin `image.tag` by digest, and verify the
images before deploying (ADR-027). Then follow the checks in the chart's install notes.

## Daily logical dump

```bash
kubectl -n notification create configmap notification-backup-scripts --from-file=deploy/backup/postgres-backup.sh
```

```bash
export POSTGRES_ADDRESS=<postgres_address> BACKUP_BUCKET=<backup_bucket_name> DISASTER_RECOVERY_REGION=<recovery region> VPC_CIDR=<vpc_cidr>
```

```bash
envsubst < deploy/k8s/aws/logical-backup.yaml | kubectl apply -f -
```

Run the restore drill against a dump from the bucket monthly ([disaster-recovery.md](disaster-recovery.md)).

## Alerts

Subscribe the on-call channel to `terraform output operations_topic_arn`. RDS publishes failed and completed
backups, failovers, failures, recoveries, low storage and maintenance there. The service's own SLO alerts come from
Prometheus as before.

## Recovery on AWS

| Scenario | How |
|---|---|
| Primary database fails | RDS fails over to the standby on its own, typically within one to two minutes. The endpoint name does not change |
| Data destroyed by mistake | Restore to a point in time into a new instance (`aws rds restore-db-instance-to-point-in-time`), verify, then point `config.database.url` at it and `helm upgrade` |
| Primary region lost | Apply this stack in the recovery region with the regions swapped, restore the database from the replicated automated backups (or the latest dump), install as above |
| Pulsar lost | Reinstall; the sweeper republishes everything not yet delivered from PostgreSQL |
| Redis lost | ElastiCache replaces the node; the rate limiter fails open meanwhile |

## Costs to expect

The baseline is roughly: three NAT gateways, an EKS control plane, six general nodes, a Multi-AZ `db.m7g.large`, two
`cache.m7g.large` nodes, Pulsar's volumes, and cross-region backup storage and transfer. Check it with the AWS
pricing calculator for your regions before applying. The largest levers are the node count, the database class, and
one NAT gateway instead of three for non-production environments.
