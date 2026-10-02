# ADR-026: Datastore availability, backups and tested recovery

- **Status:** Accepted
- **Date:** 2026-10-02

## Context

Every datastore ran as a single node. There were no backups (one had once been taken by hand), no point-in-time
recovery, no restore procedure, and no recovery point or recovery time objective. The architecture review listed this
as finding R2, and the 2026-10-01 re-review as the second half of production blocker 5. The first half (ADR-025)
deliberately left the datastores out of the Helm chart.

Working out what actually needs a backup showed a recovery gap in the application itself. The outbox sweeper
republished messages stranded in `PENDING`, `PROCESSING` and `QUEUED`, but not `RETRYING`. A retry waits in Pulsar's
retry topic, so if the broker lost it, the message stayed `RETRYING` forever, and no database backup could bring it
back.

## Decision

1. **PostgreSQL is the only store that is backed up.** Pulsar carries only message ids, and the sweeper rebuilds its
   contents from PostgreSQL. Redis holds rate-limit buckets that refill on their own. Both get availability (more
   replicas) but no backups, which removes two backup systems and their drills.
2. **The sweeper also republishes `RETRYING` messages** older than `notification.sweeper.retrying-timeout-seconds`
   (900, three times the longest backoff), so a retry that is merely waiting is never sent early. A republish is
   safe because workers claim atomically. With this, the platform recovers on its own from any loss of broker data.
3. **PostgreSQL availability:** three instances with quorum-synchronous replication to one standby, so a committed
   transaction survives the loss of the primary, plus automatic failover.
   - **On Kubernetes:** CloudNativePG, with reference manifests in `deploy/k8s/postgres`. They create the two roles of
     ADR-012 (the schema owner for migrations, and a DML-only role for the services, with default privileges on
     future tables).
   - **Managed services:** their equivalent HA option.
4. **PostgreSQL recovery:**
   - **Point-in-time recovery:** continuous WAL archiving plus a daily base backup to object storage in another
     region, kept for 30 days. Recovery always builds a new cluster up to a target time, and the cut-over is a
     `helm upgrade` gated by the migration hook.
   - **Logical dumps:** `deploy/backup/postgres-backup.sh` takes one, recording every table's row count from the
     dump's own snapshot (an exported snapshot shared by the counting session and `pg_dump`).
   - **Restore drill:** `deploy/backup/restore-drill.sh` restores a dump into a throwaway database and fails unless
     every count matches exactly and the schema history is present.
5. **Objectives,** written down in [`docs/disaster-recovery.md`](disaster-recovery.md) with the procedure for each:

   | Scenario | Data lost | Time to recover |
   |---|---|---|
   | Primary crash | None | < 1 min |
   | Destructive mistake or corruption | ≤ 5 min | < 1 h |
   | Loss of a region | ≤ 5 min | < 4 h |

6. **Tested, not assumed:**
   - **CI:** a self-test backs up a database while it is being written to, expects the drill to pass, and expects it
     to reject a backup with altered counts.
   - **Live on k3s, with CloudNativePG 1.30.1 and the Barman Cloud plugin 0.15.1:**
     - **Primary crash:** a hard crash during continuous sends was promoted in about 27 s with no failed accepts; all
       146 accepted requests were stored and sent.
     - **Point-in-time recovery:** after all messages were deleted, recovery to a moment before the delete brought
       back all 146 in 62 s.
   - **Logical dump drill:** 15,000 messages backed up during bulk sends were restored exactly.

## Options considered

- **Back up Pulsar and Redis too.** Possible, but restoring Pulsar to a point that does not match PostgreSQL would
  either resend or strand messages. Making PostgreSQL the only source of truth, and the broker rebuildable from it, is
  simpler and more correct.
- **Logical dumps only (`pg_dump` on a schedule).** Simple, but the recovery point is the dump interval (hours), and a
  large dump costs load. Continuous archiving gives minutes. The dump stays as an independent copy and for drills.
- **Asynchronous replication only.** Faster commits, but a primary crash can lose the last transactions, which here
  means accepted messages the client was told were safe (`202`). Synchronous replication adds one round trip to a
  standby to every commit. Its effect on accept latency has not been benchmarked yet; the accept-latency objective
  (ADR-024) will show it, and the standbys belong in the same region for that reason.
- **Restore in place.** Overwrites the evidence and leaves no way back if the target time was wrong. A new cluster
  plus cut-over keeps both.
- **Bundle PostgreSQL into the Helm chart.** Rejected in ADR-025: different lifecycle, and most targets use managed
  services.

## Consequences

Positive:

- Every recovery scenario has an objective, a procedure and a measurement.
- Losing the broker or Redis needs no restore.
- The backup and drill scripts are tested in CI.
- The `RETRYING` gap is closed.

Negative / accepted:

- **Commits wait for a synchronous standby.** If both standbys are lost, writes stop rather than proceed unprotected
  (`dataDurability: required`). Availability gives way to durability on purpose; the operator can relax it during an
  incident.
- **Deleting the primary pod does not give a quick failover.** It triggers a graceful smart shutdown that waits up to
  three minutes for clients, so planned maintenance must use a switchover. The runbook says so.
- **The objectives come from a small test database.** Restore time grows with size, so the monthly drill must record
  production restore times, and the base-backup frequency should follow.
- **The cloud-specific parts are not in this repository:** managed instances, buckets in another region, IAM for the
  backup credentials. They belong to the infrastructure code for the chosen cloud.

## Follow-ups

- Alerts on archiving failures and on the age of the last successful base backup, from the operator's or managed
  service's metrics. Done for CloudNativePG: `deploy/observability/prometheus/postgres-recovery.rules.yml`, with the
  runbook in docs/disaster-recovery.md.
- Infrastructure as code for the chosen cloud: managed PostgreSQL with the settings above, a cross-region backup
  bucket, a Pulsar cluster and Redis.
