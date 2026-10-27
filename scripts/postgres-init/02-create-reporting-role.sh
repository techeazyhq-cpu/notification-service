#!/bin/bash
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
# Runs once, on the Postgres container's first start against an empty data
# directory, after 01-create-app-role.sh. Creates the read-only role the
# admin API's traffic job reads tenants' messages through (ADR-050): SELECT
# on every table, nothing else, and every transaction read-only by default.
# Skipped when DB_REPORTING_USER is unset; the job then reads through the
# services' own role, as before. No exit: the image sources a script that is
# not executable (a ConfigMap mount), and exit would end its whole init.
set -euo pipefail

if [ -z "${DB_REPORTING_USER:-}" ]; then
    echo "DB_REPORTING_USER is not set; no reporting role created"
else
: "${DB_REPORTING_PASSWORD:?DB_REPORTING_PASSWORD must be set}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '$DB_REPORTING_USER') THEN
            CREATE ROLE "$DB_REPORTING_USER" LOGIN PASSWORD '$DB_REPORTING_PASSWORD';
        END IF;
    END
    \$\$;

    ALTER ROLE "$DB_REPORTING_USER" SET default_transaction_read_only = on;
    GRANT USAGE ON SCHEMA public TO "$DB_REPORTING_USER";
    GRANT SELECT ON ALL TABLES IN SCHEMA public TO "$DB_REPORTING_USER";

    -- Tables the migration job creates later are readable too, as for the services' role in 01.
    ALTER DEFAULT PRIVILEGES FOR ROLE "$POSTGRES_USER" IN SCHEMA public GRANT SELECT ON TABLES TO "$DB_REPORTING_USER";
EOSQL
fi
