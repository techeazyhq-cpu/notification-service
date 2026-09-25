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
# directory (the image's docker-entrypoint-initdb.d convention). Creates the
# least-privilege role the three services connect as, so a compromised
# service (or a SQL injection) cannot alter the schema, drop a table or read
# another database: only $POSTGRES_USER (the migration job's role, which
# owns the schema) can do DDL. See ADR-012.
set -euo pipefail

: "${DB_APP_USER:?DB_APP_USER must be set}"
: "${DB_APP_PASSWORD:?DB_APP_PASSWORD must be set}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '$DB_APP_USER') THEN
            CREATE ROLE "$DB_APP_USER" LOGIN PASSWORD '$DB_APP_PASSWORD';
        END IF;
    END
    \$\$;

    GRANT USAGE ON SCHEMA public TO "$DB_APP_USER";
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "$DB_APP_USER";
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO "$DB_APP_USER";

    -- Migrations run later, as \$POSTGRES_USER, and create tables/sequences after this script; this
    -- makes every future one grant the same rights automatically, with no changelog awareness needed.
    ALTER DEFAULT PRIVILEGES FOR ROLE "$POSTGRES_USER" IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "$DB_APP_USER";
    ALTER DEFAULT PRIVILEGES FOR ROLE "$POSTGRES_USER" IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO "$DB_APP_USER";
EOSQL
