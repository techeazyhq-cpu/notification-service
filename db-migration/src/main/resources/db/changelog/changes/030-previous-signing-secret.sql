--liquibase formatted sql

-- Copyright 2026 Vasantha Kumar
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--
-- @author Vasantha Kumar <vasantha.kumar@hotmail.com>

--changeset notification:030-previous-signing-secret splitStatements:false
--comment A rotated request-signing secret kept for a grace period (ADR-051): the secret it replaced, encrypted like the current one, and when it stops being accepted. Both are NULL outside a grace period. New nullable columns: nothing existing is locked or rewritten.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'client' AND column_name = 'previous_signing_secret'
ALTER TABLE client ADD COLUMN previous_signing_secret VARCHAR(200);
ALTER TABLE client ADD COLUMN previous_signing_secret_expires_at TIMESTAMPTZ;
ALTER TABLE client ADD CONSTRAINT ck_client_previous_signing_secret
    CHECK ((previous_signing_secret IS NULL) = (previous_signing_secret_expires_at IS NULL));
--rollback ALTER TABLE client DROP CONSTRAINT ck_client_previous_signing_secret;
--rollback ALTER TABLE client DROP COLUMN previous_signing_secret_expires_at;
--rollback ALTER TABLE client DROP COLUMN previous_signing_secret;
