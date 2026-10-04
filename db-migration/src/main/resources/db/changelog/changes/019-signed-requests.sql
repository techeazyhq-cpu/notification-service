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


--changeset notification:019-signed-requests splitStatements:false
--comment Opt-in signed client API requests (ADR-036): an encrypted signing secret and a signatures-required switch per client, and the nonces already used, so a captured request cannot be sent again. New nullable or defaulted columns and a new table: nothing existing is locked or rewritten.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'api_request_nonce'
ALTER TABLE client ADD COLUMN signing_secret VARCHAR(200);
ALTER TABLE client ADD COLUMN signing_required BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE api_request_nonce (
    client_id  UUID        NOT NULL,
    nonce      VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (client_id, nonce)
);

CREATE INDEX ix_api_request_nonce_expiry ON api_request_nonce (expires_at);
--rollback DROP TABLE api_request_nonce;
--rollback ALTER TABLE client DROP COLUMN signing_required;
--rollback ALTER TABLE client DROP COLUMN signing_secret;
