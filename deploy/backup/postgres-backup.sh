#!/usr/bin/env bash
# Copyright 2026 Vasantha Kumar
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# @author Vasantha Kumar <vasantha.kumar@hotmail.com>
#
# Logical backup of the notification database, consistent with a row count of every table taken from the same
# snapshot, so restore-drill.sh can prove a restore is complete. Complements, not replaces, continuous WAL archiving
# with point-in-time recovery (docs/disaster-recovery.md, ADR-026). Runs where psql and pg_dump exist, e.g.
#   docker run --rm -v "$PWD/deploy/backup:/scripts:ro" -v "$PWD/backups:/backups" -e DATABASE_URL postgres:16 \
#     bash /scripts/postgres-backup.sh --output-dir /backups
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: postgres-backup.sh [--output-dir DIR] [--dry-run] [--help]

Writes notification-<UTC timestamp>.dump (pg_dump custom format) and a matching .counts file (table and row count,
from the dump's snapshot) to DIR (default: current directory).

Environment:
  DATABASE_URL   libpq connection URI of the database, e.g. postgresql://user:password@host:5432/notification
EOF
}

output_dir="."
dry_run=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --output-dir) output_dir="$2"; shift 2 ;;
    --dry-run) dry_run=true; shift ;;
    --help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

: "${DATABASE_URL:?DATABASE_URL must be set}"
name="notification-$(date -u +%Y%m%dT%H%M%SZ)"
dump_file="${output_dir}/${name}.dump"
counts_file="${output_dir}/${name}.counts"

if [[ "$dry_run" == true ]]; then
  echo "Would write ${dump_file} and ${counts_file}" >&2
  exit 0
fi
mkdir -p "$output_dir"

coproc SNAPSHOT_SESSION { psql "$DATABASE_URL" --quiet --no-align --tuples-only --no-psqlrc --set ON_ERROR_STOP=1; }
trap 'kill "${SNAPSHOT_SESSION_PID:-}" 2>/dev/null || true' EXIT

run_in_snapshot_session() {
  local statement="$1" end_marker="__end_of_result__" line
  printf '%s\nSELECT %s;\n' "$statement" "'${end_marker}'" >&"${SNAPSHOT_SESSION[1]}"
  while IFS= read -r line <&"${SNAPSHOT_SESSION[0]}"; do
    [[ "$line" == "$end_marker" ]] && return 0
    printf '%s\n' "$line"
  done
  echo "The snapshot session ended unexpectedly" >&2
  return 1
}

run_in_snapshot_session "BEGIN ISOLATION LEVEL REPEATABLE READ, READ ONLY;" >/dev/null
snapshot="$(run_in_snapshot_session "SELECT pg_export_snapshot();")"

count_query="$(run_in_snapshot_session "
  SELECT string_agg(format('SELECT %L AS table_name, count(*) AS row_count FROM %I.%I', tablename, schemaname,
                           tablename), ' UNION ALL ' ORDER BY tablename)
  FROM pg_tables WHERE schemaname = 'public';")"
run_in_snapshot_session "SELECT table_name || '|' || row_count FROM (${count_query}) AS counts ORDER BY table_name;" \
  > "${counts_file}.partial"

pg_dump "$DATABASE_URL" --snapshot="$snapshot" --format=custom --no-owner --no-privileges --file="${dump_file}.partial"
run_in_snapshot_session "COMMIT;" >/dev/null

mv "${dump_file}.partial" "$dump_file"
mv "${counts_file}.partial" "$counts_file"
echo "Backup written: ${dump_file} ($(wc -l < "$counts_file") tables)" >&2
