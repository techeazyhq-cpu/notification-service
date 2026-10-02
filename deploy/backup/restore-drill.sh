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
# Restore drill: restores a backup from postgres-backup.sh into a throwaway PostgreSQL container and proves it is
# complete and usable. Every table must hold exactly the rows counted at backup time, the schema history must be
# there, and the time taken is reported as the measured restore time (docs/disaster-recovery.md, ADR-026).
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: restore-drill.sh --dump FILE [--postgres-image IMAGE] [--keep] [--help]

  --dump FILE              a .dump written by postgres-backup.sh; its .counts file must sit next to it
  --postgres-image IMAGE   server and client image for the scratch database (default: postgres:16)
  --keep                   leave the scratch database running for inspection

Needs Docker. Exits non-zero, saying why, if the restore is incomplete.
EOF
}

dump_file=""
postgres_image="postgres:16"
keep=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dump) dump_file="$2"; shift 2 ;;
    --postgres-image) postgres_image="$2"; shift 2 ;;
    --keep) keep=true; shift ;;
    --help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ -n "$dump_file" ]] || { usage >&2; exit 2; }
counts_file="${dump_file%.dump}.counts"
[[ -f "$dump_file" && -f "$counts_file" ]] || { echo "Need ${dump_file} and ${counts_file}" >&2; exit 2; }

drill_id="restore-drill-$$"
scratch_password="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
work_dir="$(mktemp -d)"
cleanup() {
  if [[ "$keep" == false ]]; then
    docker rm --force "$drill_id" >/dev/null 2>&1 || true
  else
    echo "Scratch database kept: docker exec -it ${drill_id} psql -U postgres notification" >&2
  fi
  rm -rf "$work_dir"
}
trap cleanup EXIT

scratch_sql() {
  docker exec --env PGPASSWORD="$scratch_password" "$drill_id" \
    psql --host 127.0.0.1 --username postgres --dbname notification --quiet --no-align --tuples-only \
    --no-psqlrc --set ON_ERROR_STOP=1 --command "$1"
}

started_at="$(date +%s)"
echo "Starting scratch PostgreSQL (${postgres_image})" >&2
docker run --detach --name "$drill_id" --env POSTGRES_PASSWORD="$scratch_password" --env POSTGRES_DB=notification \
  "$postgres_image" >/dev/null
until docker exec "$drill_id" pg_isready --host 127.0.0.1 --username postgres --dbname notification --quiet; do
  sleep 1
done

echo "Restoring $(basename "$dump_file")" >&2
docker cp "$dump_file" "${drill_id}:/tmp/backup.dump"
docker exec --env PGPASSWORD="$scratch_password" "$drill_id" \
  pg_restore --host 127.0.0.1 --username postgres --dbname notification --no-owner --no-privileges \
  --exit-on-error /tmp/backup.dump
restored_at="$(date +%s)"

count_query="$(scratch_sql "
  SELECT string_agg(format('SELECT %L AS table_name, count(*) AS row_count FROM %I.%I', tablename, schemaname,
                           tablename), ' UNION ALL ' ORDER BY tablename)
  FROM pg_tables WHERE schemaname = 'public';")"
scratch_sql "SELECT table_name || '|' || row_count FROM (${count_query}) AS counts ORDER BY table_name;" \
  > "${work_dir}/restored.counts"

if ! diff --unified "$counts_file" "${work_dir}/restored.counts" >&2; then
  echo "FAILED: the restored tables or row counts differ from the backup (above: - backup, + restored)" >&2
  exit 1
fi
changesets="$(scratch_sql "SELECT count(*) FROM databasechangelog;")"
latest="$(scratch_sql "SELECT id FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 1;")"
if [[ "$changesets" -eq 0 ]]; then
  echo "FAILED: the restored database has no schema history" >&2
  exit 1
fi

tables="$(wc -l < "$counts_file")"
rows="$(awk -F'|' '{ total += $2 } END { print total }' "$counts_file")"
echo "PASSED: ${tables} tables and ${rows} rows restored exactly; schema at ${latest} (${changesets} changesets);" \
  "restore took $((restored_at - started_at)) s, verification $(( $(date +%s) - restored_at )) s" >&2
