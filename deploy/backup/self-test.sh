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
# CI check for postgres-backup.sh and restore-drill.sh: backs up a throwaway database while rows are being written,
# expects the drill to pass on that backup and to fail on a copy whose counts were altered. Needs Docker. Files move
# in and out of containers with docker cp, so it runs the same on Linux and on Docker Desktop for Windows.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
postgres_image="${POSTGRES_IMAGE:-postgres:16}"
run_id="backup-self-test-$$"
password="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
work_dir="$(mktemp -d)"

host_path() {
  if command -v cygpath >/dev/null; then cygpath --mixed "$1"; else printf '%s' "$1"; fi
}

cleanup() {
  docker rm --force "${run_id}-source" "${run_id}-writer" "${run_id}-tools" >/dev/null 2>&1 || true
  docker network rm "$run_id" >/dev/null 2>&1 || true
  rm -rf "$work_dir"
}
trap cleanup EXIT

source_sql() {
  docker exec --env PGPASSWORD="$password" "${run_id}-source" \
    psql --host 127.0.0.1 --username postgres --dbname notification --quiet --no-psqlrc --set ON_ERROR_STOP=1 \
    --command "$1"
}

docker network create "$run_id" >/dev/null
docker run --detach --name "${run_id}-source" --network "$run_id" --env POSTGRES_PASSWORD="$password" \
  --env POSTGRES_DB=notification "$postgres_image" >/dev/null
until docker exec "${run_id}-source" pg_isready --host 127.0.0.1 --username postgres --quiet; do sleep 1; done

source_sql "CREATE TABLE databasechangelog (id VARCHAR(255), orderexecuted INT);
            INSERT INTO databasechangelog VALUES ('001-baseline', 1), ('002-next', 2);
            CREATE TABLE notification_message (id BIGSERIAL PRIMARY KEY, body TEXT NOT NULL);
            INSERT INTO notification_message (body) SELECT 'message ' || n FROM generate_series(1, 20000) AS n;"

docker run --detach --name "${run_id}-writer" --network "$run_id" --env PGPASSWORD="$password" "$postgres_image" \
  bash -c "while true; do psql -h ${run_id}-source -U postgres -d notification -qc \
    \"INSERT INTO notification_message (body) SELECT 'late ' || n FROM generate_series(1, 500) AS n\"; done" \
  >/dev/null

docker run --detach --name "${run_id}-tools" --network "$run_id" \
  --env DATABASE_URL="postgresql://postgres:${password}@${run_id}-source:5432/notification" "$postgres_image" \
  sleep infinity >/dev/null
docker cp "$(host_path "${script_dir}/postgres-backup.sh")" "${run_id}-tools:/tmp/postgres-backup.sh"
docker exec "${run_id}-tools" bash /tmp/postgres-backup.sh --output-dir /tmp/backups
docker rm --force "${run_id}-writer" >/dev/null
docker cp "${run_id}-tools:/tmp/backups/." "$(host_path "$work_dir")"

dump="$(host_path "$(find "$work_dir" -name '*.dump' | head -1)")"
bash "${script_dir}/restore-drill.sh" --dump "$dump" --postgres-image "$postgres_image"

altered="$(host_path "${work_dir}/altered")"
cp "$dump" "${altered}.dump"
awk -F'|' -v OFS='|' '$1 == "notification_message" { $2 = $2 + 1 } { print }' "${dump%.dump}.counts" \
  > "${altered}.counts"
if bash "${script_dir}/restore-drill.sh" --dump "${altered}.dump" --postgres-image "$postgres_image" 2>/dev/null; then
  echo "FAILED: the drill passed a backup whose counts do not match its contents" >&2
  exit 1
fi
echo "Self-test passed: consistent backup under concurrent writes, drill passes it and rejects a mismatch" >&2
