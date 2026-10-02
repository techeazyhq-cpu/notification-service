# Disaster recovery: datastores, backups and restores

How each datastore is kept available, what is backed up, how fast and how completely the platform recovers, and
the procedures for doing it. The decision record is [ADR-026](adr-026-datastore-availability-and-recovery.md).

## What needs protecting

| Store | Holds | Durable state? | Backed up? |
|---|---|---|---|
| PostgreSQL | Everything that matters: requests, messages, templates, clients, billing, audit log | Yes, the system of record | **Yes**: continuous WAL archiving and base backups, plus logical dumps for drills |
| Pulsar | Only message ids in flight (ADR-001) | No: everything in it can be rebuilt from PostgreSQL | No |
| Redis | Rate-limit token buckets | No: buckets refill on their own | No |

Losing Pulsar's data loses nothing. The outbox sweeper republishes every message still `PENDING`, `QUEUED` or
`RETRYING` in PostgreSQL once it is older than its timeout (30 s, 15 min and 15 min). Workers claim messages
atomically, so a republish never sends twice. Losing Redis means rate limits are not enforced until it returns, and
the platform keeps working (ADR-024).

## Objectives

| Scenario | Recovery point (data lost) | Recovery time | How | Measured (2026-10-02, k3s) |
|---|---|---|---|---|
| PostgreSQL primary crashes | **None**: commits wait for a synchronous standby | **< 1 min** | Automatic failover (CloudNativePG, or the managed service's HA) | Promoted in about 27 s. 80 of 80 accepts succeeded, the slowest waiting 26 s for the pool; all 146 accepted requests stored and sent |
| Data destroyed by a mistake or corruption | **≤ 5 min**: WAL is archived at least every 5 minutes (`archive_timeout`) | **< 1 h** | Point-in-time recovery into a new cluster, then cut over | All 146 messages back as of a moment 5 s before they were deleted; restore took 62 s |
| Loss of the cluster, zone or region | ≤ 5 min | **< 4 h** | Restore from the object store, kept in another region, into a new cluster | Same mechanism as the row above |
| Pulsar loses its data | None | ≤ 15 min of delay | The sweeper republishes from PostgreSQL | Covered by the sweeper tests |
| Redis lost | Not applicable | None | Fails open; buckets rebuild | Accepts kept answering 202 with Redis stopped (ADR-024) |

The times are from a single-node test cluster with a small database. Restore time grows with database size and with
the WAL to replay since the last base backup, so measure it on production data with the drill below, and adjust the
base-backup frequency if it gets close to the objective.

## PostgreSQL

### Availability

Run at least three instances across zones with one synchronous standby:

- **On Kubernetes:** [`deploy/k8s/postgres/cluster.yaml`](../deploy/k8s/postgres/cluster.yaml), with CloudNativePG
  and its Barman Cloud plugin.
- **On AWS:** RDS Multi-AZ from [`deploy/terraform/aws`](../deploy/terraform/aws), with point-in-time recovery,
  backups replicated to a second region, and RDS events for backups and failovers on an SNS topic. Its objectives and
  steps are in [aws-deployment.md](aws-deployment.md#recovery-on-aws) and ADR-028.
- **On Azure:** Flexible Server with zone-redundant HA from [`deploy/terraform/azure`](../deploy/terraform/azure),
  with geo-redundant backups to the paired region; see [azure-deployment.md](azure-deployment.md#recovery-on-azure)
  and ADR-029.
- **Other managed equivalents:** Aurora, or Cloud SQL with high availability.

Either way the services connect to one read-write endpoint that follows the primary (`notification-db-rw` with
CloudNativePG), so a failover needs no configuration change.

- **A crashed primary** is replaced automatically. The services' connection pool retries, so accepts slow down for
  the length of the failover instead of failing.
- **Planned maintenance:** use a controlled switchover (`kubectl cnpg promote notification-db <instance>`) rather
  than deleting the primary pod. Deleting it starts a graceful *smart shutdown* that waits up to three minutes for
  clients to disconnect, and the services keep their connections open, so promotion waits that long.

### Backups

1. **Continuous WAL archiving plus a daily base backup** to object storage in another region, kept for 30 days
   ([`object-store.yaml`](../deploy/k8s/postgres/object-store.yaml)). This is what point-in-time recovery uses.
   A failed archive upload is retried, but a persistent one silently stretches the recovery point, so the
   [alerts below](#backup-alerts) watch both archiving and base backups.
2. **A logical dump** with [`deploy/backup/postgres-backup.sh`](../deploy/backup/postgres-backup.sh) for drills,
   migrations between versions, and as an independent copy. It records every table's row count from the same
   snapshot as the dump.

### Backup alerts

[`postgres-recovery.rules.yml`](../deploy/observability/prometheus/postgres-recovery.rules.yml) holds four alerts,
unit-tested in CI with `promtool`. Apply [`pod-monitor.yaml`](../deploy/k8s/postgres/pod-monitor.yaml) so Prometheus
scrapes every instance and labels it with its cluster, and load the rules file next to `notification-slo.rules.yml`.
With a managed service, alert on its equivalents instead: failed WAL uploads and the age of the latest snapshot.

#### WAL archiving failing

Critical. The primary's last archive upload failed and nothing has been archived since, for 10 minutes. Every
minute this lasts adds to the data a point-in-time recovery would lose, beyond the 5-minute objective.

1. `kubectl cnpg status notification-db -n datastores` shows the archiving error and the `ContinuousArchiving`
   condition. The primary's log has the plugin's upload error.
2. Usual causes: expired or rotated object-store credentials, a deleted or renamed bucket, a changed bucket policy,
   or the network policy or firewall in front of the object store.
3. Once uploads succeed again the backlog drains on its own. Check that the alert clears and that
   `cnpg_collector_pg_wal_archive_status{value="ready"}` falls back to zero.

#### WAL archive stalled

Critical. WAL segments are waiting to be archived and nothing has been archived for 15 minutes (or ever), without a
recorded failure: a hung or misconfigured archiver rather than a rejected upload. An idle database with nothing
waiting does not raise it.

1. Check the plugin sidecar of the primary is running and the cluster's `plugins` entry still has
   `isWALArchiver: true`.
2. Watch `pg_wal` usage: segments that cannot be archived are kept, and a full WAL volume stops the database.
3. Then follow the steps for [WAL archiving failing](#wal-archiving-failing).

#### Base backup too old

Warning. The newest successful base backup is over 26 hours old (the schedule is daily at 02:00 UTC). Recovery is
still possible as long as archiving works, but it replays every WAL segment since the backup, which stretches the
one-hour recovery objective.

1. `kubectl get backups -n datastores` lists recent attempts. Check the `ScheduledBackup` is not suspended.
2. Take one now: `kubectl cnpg backup notification-db -n datastores --method plugin --plugin-name
   barman-cloud.cloudnative-pg.io`.

#### Base backup failed

Warning. The latest base backup attempt failed after the last good one. `kubectl describe backup <name> -n
datastores` gives the reason; the causes overlap with failed archiving. Fix it and take a backup by hand, as above,
rather than waiting a day for the next scheduled one.

### Point-in-time recovery

1. Find the moment just before the damage, in UTC, from the audit log (ADR-019), application logs or traces.
2. Set it as `targetTime` in
   [`point-in-time-recovery.yaml`](../deploy/k8s/postgres/point-in-time-recovery.yaml) and apply it. This creates a
   **new** cluster, `notification-db-restored`. The damaged cluster is left alone, for comparison or partial repairs.
3. Verify the restored data: row counts, the latest audit events before the target, and the schema version
   (`databasechangelog`).
4. Cut over by pointing `config.database.url` at the restored cluster's read-write service and running
   `helm upgrade`. The migration hook confirms the schema before any service restarts.
5. **Before cutting over, export the damaged cluster's rows written after the target time** (accepted messages,
   billing entries) if you need to replay or reconcile them. Recovery to a point in time discards them by
   definition.

### Restore drill

Run it at least monthly, and after any change to backups. It is the only proof that a backup can be restored.

```bash
docker run --rm -v "$PWD/deploy/backup:/scripts:ro" -v "$PWD/backups:/backups" -e DATABASE_URL postgres:16 bash /scripts/postgres-backup.sh --output-dir /backups
```

```bash
bash deploy/backup/restore-drill.sh --dump backups/notification-<timestamp>.dump
```

The drill restores into a throwaway PostgreSQL, requires every table to have exactly the row count taken at backup
time and the schema history to be present, and reports how long the restore took. It fails, saying why, otherwise.
Record the result and the restore time.

- **Checked in CI:** `deploy/backup/self-test.sh` runs both scripts on every build. It backs up while rows are being
  written and expects the drill to reject a backup whose counts were altered.
- **Checked live:** 15,000 messages backed up during bulk sends were restored exactly in 15 s.

For point-in-time recovery, drill by restoring into a scratch cluster, as above, without cutting over.

## Pulsar

Run a cluster with at least three bookies (ensemble 3, write quorum 3, ack quorum 2) and two or more brokers: the
Apache Pulsar Helm chart, an operator, or a managed service. No backup is needed (see above).

After any broker data loss, no action is needed. The sweeper republishes stranded messages within 15 minutes, which
shows as `notification_backlog_oldest_age_seconds` rising and then falling (ADR-024).

## Redis

A managed Redis (ElastiCache, Memorystore, Azure Cache) or Redis with Sentinel gives availability. Nothing in it
needs a backup. While it is unavailable, `NotificationRateLimiterFailingOpen` fires and limits are not enforced.
